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
    /** SPEC_COMBAT 9 / 10: a room / window opened in a fight takes the controller through ONE secure tap on Interact's enter cell */
    private val fightEnter = FightEnter()
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
    /** Bags' pin picker (the radial's "+"), hung over the room; Home's radial drives it */
    private lateinit var pinPicker: BagsPinPicker
    // our own Battle.net sign-in (LATER 29): the prompt, the login screen's watcher, and the line the start screen showed before it
    private lateinit var loginPrompt: HomeLoginPrompt
    /** the launcher's setup on the bottom screen (Track C): Play + one pop-up at a time ([SetupBridge]) */
    private lateinit var setupPanel: SetupPanel
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
    /** the Spellbook room (spells session), and what it brings over the whole screen (the Bind box, the "New spell" card) */
    private lateinit var spellbookView: SpellbookView
    // the CONTROLLER ROOM (spells session's view; Home hosts it in Chat's old spot on the bar, 2026-10-05)
    private lateinit var controllerView: ControllerView
    private lateinit var spellsOverlay: SpellsOverlay
    private var spellsPad: JSONObject? = null
    // the user's "Both" ruling after test20: the In-combat backup and the clean exit
    private lateinit var combatKeep: CombatKeepHost
    private var lastBook: SpellsModel.Book? = null
    private var lastGameMenu: JSONObject? = null
    private var lastAddonAt = 0L
    // SvKeep (the user's pick after test22): our tables confirmed this run (a non-empty first word, or the player changed a setting)
    private val svArmed = HashSet<String>()
    private val svSeen = HashSet<String>()                              // the run's FIRST word per table decides (a partial restore later must not)
    private var svRun = Long.MIN_VALUE
    private val SV_PLAYER_OPS = setOf("combat", "cface", "uncombat", "uncface", "clear", "command", "spell", "move", "bind", "rspell")
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

    private var tail: LogTail? = null
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
                startView.line = state.why
                startView.visibility = View.VISIBLE
                startView.invalidate()
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

    fun onCreate(savedInstanceState: Bundle?) {
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
        GameUi.init(this)
        GameInput.onStatus = { s -> ui.post { status.text = s.take(110) } }
        // NOTE: do NOT set FLAG_NOT_FOCUSABLE. It leaves display 4 with no focused window, so
        // the first touch waits 5 s for a focus target and the system ANRs us at 0% CPU. This
        // device has per-display focus, so holding focus here does not take it from the game.
        shell.hostWindow()?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Theme.ink)
        }
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
        bagsView = BagsView(this)
        // bags controller mode (bags session, AGENT_LOG 2026-09-28): the Bags room while the addon holds the controller
        bagsView.onController = { on, why -> onBagsController(on, why) }
        chatView = ChatView(this) { cmd -> GameInput.chat(cmd) }
        // Chat's rework (AGENT_LOG 2026-10-07): typed and quick chat go out through the add-on's chat rows, in ordered batches
        chatView.tapper = { cells, gap -> TapBridge.tapsSpaced(this, questsPad, cells, gap) }
        characterView = CharacterView(this)
        // B leaves the cursor -> Home (user ruling, every room)
        characterView.onLeave = { roomLeft(Room.CHARACTER) }
        // tab 4 (PvP): the PVP session's page inside the room's frame (its PvpRankTab, UI Art's SPEC 6)
        characterView.pvpDrawer = PvpRankTab::draw
        PvpRankTab.onChange = { characterView.invalidate() }
        // LATER item 11 (the user: "when reloading from the settings, the bottom screen should enter its loading menu and return
        // to home"): the key sent, the start screen covers the bottom screen until the game's new hello, then Home
        val reload = { if (GameInput.reload()) reloadStarted() }
        // SettingsView (map session) in two parts, Home's section between them (the user's order, 2026-10-06)
        settingsView = SettingsView(this, SettingsView.Part.TOP, reload)
        settings = SettingsRoom(this, settingsView, SettingsView(this, SettingsView.Part.REST, reload), { questsPad })
        settings.pad.onLeave = { show(Room.HOME) }
        settingsRoom = settings
        // the DS WoW toolbar's switch lives in Home's Settings section since 2026-10-06 (renamed from "D-pad room bar")
        settings.homeSection.dpadState = { lastDpad }
        settings.homeSection.onDpad = { on -> dpadSet(on) }
        homeView = HomeView(this, mapView, castView, chatView) { r -> show(r) }
        // LATER item 10 (map session): a tap on a numbered quest bubble, or on a quest in the full map's list, focuses it through
        // Home's ONE focus path (its checks and WCHOME logging); a refusal shows on the map
        mapView.onFocusQuest = { id -> homeView.focusQuest(id) { mapView.note(it) } }
        // the Map room's X on its list (map session, the user's quest-list feedback): untrack through Home's one quest-action path
        mapView.onUntrackQuest = { id -> homeView.untrackQuest(id) { mapView.note(it) } }
        // A on the focused quest clears the focus (Quests q9, qact 39)
        mapView.onUnfocusQuest = { id -> homeView.unfocusQuest(id) { mapView.note(it) } }
        // LATER item 5, the generic controller guests (Quests.lua q6): B with nothing nearer to do leaves with B -> Home
        questsView.onLeave = { show(Room.HOME) }
        // B leaves the chat for Home: the disc's press (UI Art's push + gold flash; the user's Home-button feedback 2026-10-01)
        chatView.onLeave = { guestTap(GuestPad.leaveB(questsPad)); strip.homePressed(); show(Room.HOME) }
        // only while a chat guest shows (the Chat room, or Home's chat module): the ChatView's scene also changes when it is given
        // back, and a bnext then reached the addon after the free ("no guest holds", batch 12's touch test)
        chatView.onBackChanged = { home ->
            if (room == Room.CHAT || (room == Room.HOME && homeView.chatModuleOn)) guestTap(GuestPad.bnext(questsPad, home))
        }
        mapView.onLeave = { show(Room.HOME) }
        mapView.offMapSpots = { qid -> worldMapRoom.questWorldSpots(qid) }      // Map: the arrow to a quest in another zone
        mapView.onProbeOptions = { bits -> worldMapRoom.sendProbeOptions(bits) }   // Map: the minimap probe's options to the add-on
        worldMapRoom.onLeave = { show(Room.HOME) }
        worldMapRoom.onFocusQuest = { id -> homeView.focusQuest(id) { } }
        worldMapRoom.onUntrackQuest = { id -> homeView.untrackQuest(id) { } }
        worldMapRoom.onUnfocusQuest = { id -> homeView.unfocusQuest(id) { } }
        // LATER item 7: Select opens a cursor on the bar; A on a room is what a touch on its picture does
        // the swirl (Home) picked on the bar = the pause wheel, on Home and in rooms (the user 2026-10-09: "if I select the spiral I want
        // that to open the pause menu"; "Wheel everywhere"); B still goes Home from a room, a room slice switches rooms
        barSelect = HomeBarSelect(this, strip, { questsPad }) { r -> if (r == Room.HOME) openPause("the bar's swirl", byStart = false) else pickRoom(r) }
        // LATER item 23a (the user: "the buttons at the top still show their presses, and that's confusing"): quiet while the cursor is up
        barSelect.onOpenChanged = { open -> castView.quietPresses = open }
        // the D-pad layout (the user's design 2026-10-05; Hub hub5, Spells' router): right = Home's map module, left = the Chat room
        // ("The full Chat room": one way into chat), up = the consumables radial (batch b; nothing yet). Down is the bar's own
        barSelect.onDpad = dpad@{ k ->
            // while the radial shows, its own reader moves round the ring; the router's map / chat / home words wait
            if (::radialView.isInitialized && radialView.isOpen && k != "radial" && k != "sradial") return@dpad
            // the pause menu reads its own controller while it shows: only another Start ("pause") reaches it
            if (::pauseView.isInitialized && pauseView.isOpen && k != "pause") return@dpad
            when (k) {
                // Spells' router: Start with nothing holding the controller (the user's Q1: a module that holds Start keeps it)
                // the router's word comes ~0.1-0.2 s after the app's own Start read (startPressed) already acted: dropped then
                "pause" -> if (SystemClock.uptimeMillis() - startReadAt > START_DEDUPE_MS && room == Room.HOME &&
                    !(::radialView.isInitialized && radialView.isOpen)) pauseView.toggle()
                "map" -> { if (room != Room.HOME) show(Room.HOME); homeView.post { homeView.openMapModule() } }
                "chat" -> if (room != Room.CHAT) show(Room.CHAT)
                "radial" -> radialView.toggle()
                "sradial" -> radialView.toggle("sradial")     // Spells' spell radial (Hub's WowCompanionPadSpellRadial)
            }
        }
        barSelect.roomNow = { room }        // Select's cursor starts on the room on screen (never on Home: the user's skip-Home rule)
        // the Home button's B follows Home's own state at once (a module full screen shows B; user 15:08)
        homeView.onStateChanged = { strip.bGoesHome = discShowsB() }
        homeView.onBHome = { strip.homePressed() }
        // Home's controller reader passes each held mask to Interact's pop-up while it shows: its Make Space ring follows the real A
        // press (display only; one getevent). In a room no reader of Home's runs: the ring falls back to Interact's document
        homeView.onPadHeldAny = { m ->
            // our own sign-in's row on the start screen takes the pad while it shows (LATER 29); then Interact's loot ring
            // (the edge is taken on EVERY mask, whatever the route, so a press held through a route change is not swallowed later)
            val down = m and loginPadHeld.inv(); loginPadHeld = m
            // B closes the game keyboard the sign-in opened (the game gets the same B: nothing else here takes it then)
            if (SetupBridge.keyboardOpen && PadInput.isHeld(down, PadInput.SLOT_FACE_RIGHT)) {
                NativeLogin.logEvent("sign-in: keyboard closed (B)"); SetupBridge.keyboard(false)
            }
            else if (::setupPanel.isInitialized && !loginPrompt.isOpen && setupPanel.pad(down)) Unit      // the setup's pop-up (Track C)
            else if (startView.visibility == View.VISIBLE && startView.login != StartView.Login.NONE && !loginPrompt.isOpen &&
                setupView.visibility != View.VISIBLE) loginPad(down)
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

        interactView = InteractView(this)
        dialogView = DialogView(this)
        startView = StartView(this)
        // INSIDE THE ONE APP (the native login.txt sign-in; the user: "injecting the keys is not the way to go"): the row never types -
        // its button saves / updates the login, which the game uses by itself at its next start (NativeLogin)
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
        AllIcons.busy = { homeView.inFight() }                            // the background icons wait while the player fights
        if (LauncherHooks.pkg != null) {
            NativeLogin.onTrafficSample = { rx, tx -> signInCheck.traffic(NativeLogin.firstReadAt, rx, tx) }
            ui.post(signInTick)
        }
        // the saved login is changed in Settings (the user 2026-10-08: "Change saved login" moves there)
        settingsView.loginSaved = { HomeLoginStore.has(this) }
        settingsView.onLogin = { loginPrompt.open() }
        settingsView.loginRow = true
        if (LauncherHooks.pkg != null) {
            startView.firstLabel = "Save your Battle.net login"; startView.firstW = 680f      // the longer words
            sweepTrigger()
            NativeLogin.onChange = { if (!shell.hostGone()) loginShow() }
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
        spellsOverlay = SpellsOverlay(this,
            // the card waits while an NPC window or a game dialog is up
            blocked = { interactView.visibility == View.VISIBLE || dialogView.visibility == View.VISIBLE },
            onOpen = { id -> show(Room.SPELLBOOK); spellbookView.openAt(id) },
            // ONE controller reader at a time (home's finding): the Bind box reads the triggers only while the Cast view's is off
            otherReader = { castView.isShown },
            // the Bind box's controller picture, drawn by the Cast room as it draws its own sets (false = no layout yet: stand-ins)
            drawSet = { c, set, dpad, face, lit, icon -> castView.drawSet(c, set, dpad, face, lit, icon) },
            onTap = { col -> spellsTap(col) })                    // b37: the Bind box's Normal / In combat
        spellsOverlay.visibility = View.GONE
        radialView = HomeRadialView(this, strip, { questsPad }) { cells -> guestTap(cells) }
        pinPicker = BagsPinPicker(this)
        radialView.picker = pinPicker
        pauseView = HomePauseView(this, strip, { questsPad }) { cells -> guestTap(cells) }
        pauseView.onSlide = { t -> pauseSlideAll(t) }
        // the open's REVERSE after the wheel has gone (the user 2026-10-09: "it needs to have a reverse animation for when the B button
        // gets pressed"): the same quick time; input is already the room's / Home's (the wheel is gone in the B frame)
        pauseView.onClose = { from ->
            pauseBack?.cancel()
            if (from <= 0f) pauseSlideAll(0f)
            else pauseBack = android.animation.ValueAnimator.ofFloat(from, 0f).apply {
                duration = (UiKit.PAUSE_OPEN_MS / HomePauseView.SPEED * (from / UiKit.PAUSE_OPEN_MS)).toLong().coerceAtLeast(16L)
                addUpdateListener { a -> slideT = a.animatedValue as Float; pauseSlideAll(slideT) }
                start()
            }
        }
        pauseView.onPick = { s -> pausePick(s) }
        shell.setHostContent(FrameLayout(this).apply {
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
            // the radial's pin picker: over the room only (the bar stays below it)
            addView(pinPicker, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { bottomMargin = Strip.HEIGHT })
            addView(interactView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // the game's dialogs lie over everything, an NPC window included
            addView(dialogView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // the start screen lies over all of it, and goes with the game's first current word
            addView(startView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // the launcher's setup (Track C): over the start screen before Play, its sign-in pop-ups over the rooms in the game
            addView(setupPanel, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // our own Battle.net sign-in's prompt (LATER 29): over the start screen, whose gear / first button opens it
            addView(loginPrompt, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // the self-setup's prompt and progress: over the start screen too
            addView(setupView, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        })
        show(Room.HOME)

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
                strip.bGoesHome = discShowsB()      // on Home a B with no fresh word goes back to the house
                ui.postDelayed(this, 1000)
            }
        })
    }

    fun onStart() {
        DirectPad.start()
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
        DirectPad.shutdown()
        // nothing of the game's UI stays hidden without its replacement in view
        GameUi.onVisible(false)
        // Select is the game's again while the app is away (the bar selector asks for it back when it returns)
        barSelect.appAway()
    }

    private fun show(r: Room, byB: Boolean = false) {
        flushBLeave()
        // the pause menu never stays over a room change (a whisper's jump, a game window); its own picks run after it has folded
        if (::pauseView.isInitialized && pauseView.isOpen) pauseView.shut()
        if (::pauseView.isInitialized && r != room) pauseView.clearHint()
        // any room change ends the bar selector first. NO GAP (the user 2026-10-06): into another room the bar's hold is KEPT for that
        // room's own take to pre-empt (capture v7 / v12; HomeBarSelect.closeFor, RoomHandOver.Bar); Home or the same room: freed now
        barSelect.closeFor(r, room)
        // and the room's combat cue: a room shown anew is cued only once a document says it holds through the fight
        if (::roomCue.isInitialized && r != room) roomCue.off()
        // MID-FIGHT (capture v9): a room holding through a fight lets go ONLY through the capture's secure leave cell - one tap, the same
        // for every room and EVERY route that leaves it (a touch on a room or the Home disc, the bar cursor, a game window's jump, a
        // whisper, the map pin). The rooms' own release / take taps below are refused in a fight (harmless)
        val fightLeft = r != room && fightLeave.cell != null
        if (r != room) fightLeave.leaveNow()?.let { (row, col) ->
            val sent = TapBridge.tap(this, questsPad, row, col) { }
            Log.i("WCHOME", "fight leave: $row $col ${if (sent) "sent" else "not sent"} ($room -> $r)")
            ui.postDelayed({
                fightLeave.retryNow()?.let { (rr, cc) ->
                    val again = TapBridge.tap(this, questsPad, rr, cc) { }
                    Log.i("WCHOME", "fight leave: once more $rr $cc (the room still held) ${if (again) "sent" else "not sent"}")
                }
            }, FightLeave.RETRY_MS)
        }
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
        strip.bGoesHome = discShowsB()       // the disc follows the room at once (red B in every room)
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
        if (socialIn) {
            socialHold(0)
            socialView.onShown()
        }
        if (gfIn) groupFinderView.onShown()
        if (calIn) calendarView.onShown()
        // ... and the same rooms take the controller here, for every route that brings them up (each tap guarded on the room not
        // holding already: a room the GAME opened holds before its jump lands here, so nothing is tapped then - the trainer lend too)
        if (!fightLeft) touchRoomsEnter(was, r)
        // a room shown in a fight whose take is pending (doc.enter came first) gets its enter tap now
        fightEnterCheck()
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
     * SPEC_COMBAT 9 / 10: the pending enter (Interact's doc.enter) is tapped ONCE when what waits is actually on screen - its room shown
     * (HomeBack.roomsOf), or for "npc" Interact's window up (never a hidden or late one: Interact's ruling) - and once more after
     * RETRY_MS while the same appearance is still pending (a tap lost to the park point; the cell is safe to repeat). Called from every
     * current interact document and from show().
     */
    private fun fightEnterCheck() {
        val w = fightEnter.want ?: return
        val onScreen = if (w.forId == "npc") ::interactView.isInitialized && interactView.visibility == View.VISIBLE
            else room.name in HomeBack.roomsOf(w.forId)
        if (!onScreen) return
        val id = w.forId
        fightEnter.enterNow(id)?.let { (row, col) ->
            val sent = TapBridge.tap(this, questsPad, row, col) { }
            Log.i("WCHOME", "fight enter: $row $col for $id ${if (sent) "sent" else "not sent"}")
            ui.postDelayed({
                fightEnter.retryNow(id)?.let { (rr, cc) ->
                    val again = TapBridge.tap(this, questsPad, rr, cc) { }
                    Log.i("WCHOME", "fight enter: once more $rr $cc for $id (still pending) ${if (again) "sent" else "not sent"}")
                }
            }, FightEnter.RETRY_MS)
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

    /**
     * A TOUCH ON THE SWIRL = THE BAR CURSOR'S PICK OF IT (the user on RC4: "tapping on the swirl should do the same thing as picking it
     * with the controller"): the pause wheel, on Home and in rooms. What the cursor can never reach keeps the disc's B: a game window
     * whose B goes Home closes with its own close (the 2026-10-01 ruling) and Home shows; on Home, a full-screen module or the buff list
     * steps back (the B the disc shows there, the user 15:08).
     */
    private fun swirlTouched() {
        if (strip.bGoesHome && ::interactView.isInitialized && interactView.closeByTouch()) {
            Log.i("WCHOME", "swirl touched while it shows B: the game window closes (its own close)")
            pickRoom(Room.HOME); return
        }
        if (room == Room.HOME && homeView.bGoesHome()) { pickRoom(Room.HOME); return }
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
            HomePauseModel.Kind.HOME -> if (s.key == "buffs") homeView.openAuras(debuff = false)
            else -> {}
        }
    }

    /** the Home disc shows B in every room (HomeDisc: no blue flash on a room-to-room switch); on Home it follows Home's own state */
    private fun discShowsB(): Boolean = HomeDisc.showsB(room != Room.HOME, homeView.bGoesHome())

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
        Log.i("WCHOME", "chat room: hold${if (attempt > 0) " again ($attempt)" else ""} ${if (sent) "sent" else "NOT sent (no pad, or the game is not live)"}")
        val mine = ++chatHoldTry
        if (chatAsk.asked(sent)) ui.postDelayed({ if (mine == chatHoldTry && room == Room.CHAT) chatHold(attempt + 1) }, HoldAsk.EVERY_MS)
    }
    private val chatAsk = HoldAsk()
    private var chatHoldTry = 0

    /** the shared capture's holder as last known (the interact document's `holder`, or a guest's own hold / free answer) */
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
        fun live(id: String): () -> Boolean = { guestShown(id) && capHolder == "pad:$id" }
        val lr = setOf(DirectPadKeys.LB, DirectPadKeys.RB)
        DirectPad.register(DirectPad.Reg("quests", DirectPadKeys.EIGHT, repeat = setOf("PADDUP", "PADDDOWN"), active = live("quests")) { k, down, rp ->
            if (down) questsView.onDirect(k, rp) })
        DirectPad.register(DirectPad.Reg("settings", DirectPadKeys.EIGHT, active = live("settings"), onKey = synth("settings") { settings.pad.onPad(it) }))
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
            if (k == "PADFORWARD" && down) startPressed()
            if (k == "PAD2" && down) bPressed()
            if (down && k == "PADDDOWN" && room == Room.HOME && capHolder.isEmpty() && !DirectPad.sideHeld()
                && interactView.visibility != View.VISIBLE && dialogView.visibility != View.VISIBLE
                && spellsOverlay.visibility != View.VISIBLE && startView.visibility != View.VISIBLE) barSelect.openEarly(room)
        }
        DirectPad.register(DirectPad.Reg("home", DirectPadKeys.EIGHT + lr + setOf("PADBACK"), active = live("home"),
            onKey = synth("home") { if (setupPad) setupView.padKey(it.optString("k")) else homeView.auraPad(it) }))
    }

    private fun guestShown(id: String): Boolean = when (id) {
        "settings" -> room == Room.SETTINGS
        "quests" -> room == Room.QUESTS
        "chat" -> room == Room.CHAT
        "map" -> room == Room.MAP
        // during the no-gap hand-over the bar still holds ON PURPOSE: its presses are dropped (HomeBarSelect.onPad, closed), never
        // re-freed here (a free then would open the very gap the hand-over avoids); the hand-over frees it itself when it must
        "bar" -> barSelect.open || barSelect.handingOver
        "home" -> room == Room.HOME && (homeView.homeGuestWanted() || setupPad)
        // the consumables radial holds only while its wheel shows (b57, 13:29: the close's free went out before the capture had taken
        // the hold, so "radial" kept the controller with no wheel; a late "hold ok", a press or the holder report now frees it again)
        "radial" -> (::radialView.isInitialized && radialView.isOpen) || (::pauseView.isInitialized && pauseView.isOpen)
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
                        startView.line = "Waiting for the game."; startView.visibility = View.VISIBLE
                    }, 4_000L)
                }
            }
        }
    }

    private fun handleMessage(msg: JSONObject) {
        // first, for every document: is the game live, and is a text box open over there
        val current = GameInput.note(msg)
        docCurrent = current
        if (current) lastAddonAt = SystemClock.elapsedRealtime()           // the clean exit's "past the login screen"
        // the re-login: the world is back, so a waiting login.txt goes (NativeLogin.rearm wrote it when the world closed)
        if (current && LauncherHooks.pkg != null) NativeLogin.worldSeen()
        // smoothness A: an answer document's arrival, for the latency chain (inside the launcher only)
        if (LatLog.on) msg.optString("type").let { t -> if (t.endsWith("ack") || t.endsWith("use")) LatLog.mark("doc", "$t gt=${msg.optLong("_gt", -1L)}") }
        if (current && !docFlowSeen) { docFlowSeen = true; Log.i("WCHOME", "docs: first current document (${msg.optString("type")})") }
        // the interiors bake (Track C) runs while the game is there and quiet; it waits while it fights or loads
        if (current) IndoorSchedulerStep.noteAlive(homeView.inFight() || setupLoading || startView.visibility == View.VISIBLE)
        // a reload from the Settings keeps it up until the new hello (the game writes on for a moment before it reloads)
        if (current && startView.visibility == View.VISIBLE && reloadAt == 0L && !setupLoading &&
            ArtHarvest.state !is ArtHarvest.State.Failed) startView.visibility = View.GONE
        // where the character stands chooses the scene behind every waiting screen (UI Art's Scene)
        msg.optJSONObject("map")?.let { if (it.has("inst")) Scene.note(it.optInt("inst", -1)) }
        when (msg.optString("type")) {
            "full" -> {
                msg.optJSONObject("map")?.let {
                    // the addon's session list heals a lost "poi" line; then the stored list
                    // (every session, in MapView's shape) replaces it
                    PoiHarvest.ingestAll(it.optJSONArray("svc")); PoiHarvest.attach(it)
                    lastMap = it; lastMapAt = System.currentTimeMillis(); mapView.update(it, 0); worldMapRoom.update(it)
                    if (current) GameUi.onTracking(it.optJSONObject("track"))     // LATER 25: the "While tracking" minimap
                }
                msg.optJSONObject("actionbar")?.let { castView.update(it) }
                homeView.onFull(msg, current)
                msg.optJSONObject("bags")?.let { bagsView.update(it, current); pinPicker.update(it); characterView.updateBags(it); if (ProfessionsStore.onBags(it)) professionsView.refresh() }   // old = no room switch, no tap (bags)
                status.text = "gaps ${tail?.gapCount() ?: 0}   v${msg.optString("v", "?")}"
            }
            "pos" -> msg.optJSONObject("map")?.let {
                mapView.updatePos(it, msg.optLong("_gt", -1L)); worldMapRoom.updatePos(it)
                // the background minimap step builds the player's own zone first (Map's nearest-first hook)
                if (it.has("mapID") && it.has("wx")) MapHarvestSteps.near = Triple(it.optInt("mapID"), it.optDouble("wx"), it.optDouble("wy"))
                // ... and the interiors bake its building first
                IndoorSchedulerStep.notePosition(it)
                lastMapAt = System.currentTimeMillis()
                lastMap?.let { cur ->
                    for (k in listOf("x", "y", "wx", "wy", "facing", "zone", "subzone", "mapID"))
                        if (it.has(k)) cur.put(k, it.get(k))
                }
            }
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
            "bye" -> if (current) byeShow()
            "chat" -> msg.optJSONObject("chat")?.let { chatView.update(it); homeView.onChat(it) }
            // the game's world notice (Chat chat21: the shard transfer's countdown) over Home's chat: current only (a replayed one is old)
            "notice" -> if (current) homeView.onNotice(msg)
            "chatt", "chatq", "chatlink" -> chatView.onDoc(msg)      // Chat's rework: typed / quick chat answers, link tooltips
            "quests" -> msg.optJSONObject("q")?.let {
                it.optJSONObject("pad")?.let { p -> questsPad = p }
                questsView.update(it); homeView.onQuests(it, current); mapView.onQuests(it); worldMapRoom.onQuests(it)
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
            "radial", "sradial" -> radialView.onDoc(msg)
            "radialuse", "sradialuse" -> if (current) radialView.onUse(msg)
            "pauseack" -> if (current && ::pauseView.isInitialized) pauseView.onAck(msg)
            // the Game Menu on the bottom screen (Spells r12): the game's list + its countdown, and the cells' answers
            "gamemenu" -> { if (current) lastGameMenu = msg; if (current && ::pauseView.isInitialized) pauseView.onGameMenuDoc(msg) }
            "gmack" -> if (current && ::pauseView.isInitialized) pauseView.onGameMenuAck(msg)
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
                    "radial" -> if (::pauseView.isInitialized && pauseView.isOpen) pauseView.onGuestAck(msg)
                        else if (::radialView.isInitialized) radialView.onGuestAck(msg)
                    "home" -> { setupHoldAck(msg); homeView.auraGuestAck(msg) }
                }
            }
            // both may be waiting: each takes only the answers to what it sent
            "questack" -> { questsView.ack(msg); homeView.ack(msg); if (current) worldMapRoom.onQuestAck(msg) }
            // character's aura documents: Home's buff / debuff buttons and their list (listed in AGENT_LOG first)
            "auras" -> homeView.onAuras(msg, current)
            "auraack" -> homeView.auraAck(msg)
            "interact" -> {
                interactView.update(msg, current)
                // Home > Settings' loot window row (r-loot-on-top): the add-on's word; a replayed document only draws
                if (current) HomeLootSwitch.note(msg)
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
                // ... and a room or window wanting the controller in a fight waits for ONE tap on the enter cell (doc.enter)
                if (current) { fightEnter.note(msg); fightEnterCheck() }
                // B handed the controller back (interact's shared capture, rel.n / rel.by): Home, as the user ruled
                // ... and the disc that showed B gets its press (the user: "Pushing B should have the button push effect and do the
                // yellow flash"), on Home too (a window closed over Home)
                if (homeView.backToHome(msg, current)) {
                    // a "B" release naming the room THE APP just left (within 1 s) is that leave's echo, not the player's B: it must not
                    // pull the screen Home from the room the player picked (Social's touch check 2026-10-02 09:28:48)
                    val who = homeView.bReleasedBy()
                    if (HomeBack.isOwnLeave(who, leftIds, leftAt, SystemClock.uptimeMillis()))
                        Log.i("WCHOME", "release by B from $who: the app's own leave, not the player's B (stays on $room)")
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
                strip.bGoesHome = discShowsB()
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
                    dialogView.visibility == View.VISIBLE || spellsOverlay.visibility == View.VISIBLE || startView.visibility == View.VISIBLE)
            "interactack" -> {
                if (msg.optString("cmd") == "release npc" && !msg.optBoolean("ok", true)) {
                    val now = SystemClock.uptimeMillis()
                    if (now - npcRefusedAt >= NPC_BACKOFF) Log.i(HomeView.TAG, "guest npc: free refused (${msg.optString("why")}), next try in 30 s")
                    npcRefusedAt = now
                }
                interactView.ack(msg)
            }
            "dialogs" -> dialogView.update(msg, current)
            "dialogack" -> dialogView.ack(msg)
            "pvp" -> PvpModel.update(msg)   // the PvP rank (Character room tab 4) + the battleground queues (pvp session)
            "hub" -> GameUi.onHub(msg, current)
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
            "spells" -> SpellsModel.parse(msg)?.let { lastBook = it; spellsPad = it.pad; spellbookView.update(it); controllerView.update(it); spellsOverlay.onBook(it) }
            // CONTROL documents act only when CURRENT: one replayed from the backlog at app start tells who held the controller THEN, and
            // must not open the room or a page, bring up the Bind box or a Talents box, or leave a stale "held" behind (a later room change
            // would tap close). Home's finding, AGENT_LOG 9596d1a. The book, the tree and the chosen talent replay: the last known state shows.
            "spellsctl" -> if (current) SpellsModel.parseCtl(msg)?.let { c ->
                spellbookView.onCtl(c); controllerView.onCtl(c); spellsOverlay.onCtl(c)
                // the page the controller drives (Book.lua b3 `pg`) picks the room: the game's own Talents / Spellbook buttons bring theirs
                // up; between the two rooms the page follows the bar (no taps here: see pickRoom)
                // (the Talents page flag BEFORE the jump: show()'s hand-over reads it, so a page the game moved is not moved again)
                talentsView.held = c.on && c.talentsPage
                if (c.on) {
                    // the page picks the room: the Controller room's own page (b17 croom), Talents, or the book
                    val want = if (c.croom) Room.CONTROLLER else if (c.talentsPage) Room.TALENTS else Room.SPELLBOOK
                    // via "trainer": Context Menus' trainer lent the controller on Y (the user: "Y opens the Spellbook room; B comes back")
                    if (room != want && (c.via == "button" || c.via == "trainer" || room == Room.SPELLBOOK || room == Room.TALENTS ||
                            room == Room.CONTROLLER)) show(want)
                }
            }
            "spellsack" -> if (current) SpellsModel.parseBind(msg)?.let { spellsOverlay.onBind(it); spellbookView.invalidate() }
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
                if (::combatKeep.isInitialized) combatKeep.onMap(it, current)     // the In-combat backup (the user's "Both" ruling)
                spellbookView.onPadMap(it); controllerView.onPadMap(it); homeView.onPadMap(it); spellsOverlay.onPadMap(it)
                // the D-pad room bar (Spells' m24): the Settings row's state, and the once-per-character prompt
                it.dpad?.let { d ->
                    // the character now: the wheels' kept lists of another character go (the per-character ruling)
                    if (current && !d.name.isNullOrEmpty() && ::radialView.isInitialized) radialView.character("${d.name}-${d.realm ?: ""}")
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
            // the fight faces' cooldowns (Spells' m12): current only - a replayed one would start a sweep that already ran out
            "facecd" -> if (current) PadMapModel.parseCd(msg)?.let { homeView.onFaceCd(it) }
            // the fight flag also from Spells' facewatch (Tracking after test18): Home's D-pad picture follows the newest word
            "facewatch" -> if (current) homeView.onFightWord(msg.optBoolean("fight", false))
            "padack" -> if (current) PadMapModel.parseAck(msg)?.let {
                spellbookView.onPadAck(it); controllerView.onPadAck(it)
                // the player changed an In-combat setting: the backup follows the store from now on (test22 P0)
                if (msg.optBoolean("ok") && msg.optString("op") in setOf("combat", "cface", "uncombat", "uncface", "clear") &&
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
        val st = if (restartOnSave) SetupBridge.signIn else if (LauncherHooks.gameUp()) {
            // a new client run starts clean
            if (LauncherHooks.gameStartSec != signInRun) {
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
        val restart = st != SetupFlow.SignIn.NONE && signInCheck.restartOffered
        if (st != SetupBridge.signIn || restart != SetupBridge.restartOffered) {
            NativeLogin.logEvent("sign-in: $st${if (restart) " (restart offered)" else ""}" +
                if (NativeLogin.firstReadAt > 0) ", ${(now - NativeLogin.firstReadAt) / 1000} s after the read" else "")
        }
        // the code box: the top screen's keyboard opens by itself, once as the game asks ("Keyboard" toggles it after that)
        if (st == SetupFlow.SignIn.AUTH && SetupBridge.signIn != SetupFlow.SignIn.AUTH) {
            NativeLogin.logEvent("sign-in: authenticator code asked, keyboard opened"); SetupBridge.keyboard(true)
        }
        SetupBridge.signIn(st, restart)
        if (st == SetupFlow.SignIn.NONE && SetupBridge.keyboardOpen) { NativeLogin.logEvent("sign-in: keyboard closed (past the login screen)"); SetupBridge.keyboard(false) }
    }
    private val NO_FILE_LOGIN_S = 30L
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
        val log = gameLog()
        if (log == tailLog && tail != null) return          // the launcher saves the same folder on every check: no restart then
        tailLog = log
        tail?.stop()
        // inside the launcher there is only its own game: no GameNative log ahead of it
        val follow = if (BuildConfig.SHARE || LauncherHooks.pkg != null) listOf(log) else listOf(LogTail.DEFAULT_PATH, log)
        Log.i("WCLOG", "game log: $log (located ${game?.root ?: "none"}, share ${BuildConfig.SHARE})")
        tail = LogTail(
            follow.first(),
            onMessage = { msg -> ui.post { handleMessage(msg) } },
            onStatus = { s -> ui.post {
                status.text = s.take(110)
                // the other game started: is IT set up for the companion?
                if (s.startsWith("following the game's log")) ui.postDelayed({ setupCheck() }, 3_000L)
            } },
            follow = follow,
        ).also { it.start() }
    }

    fun onDestroy() {
        ArtHarvest.offState(iconArtState)
        IndoorSchedulerStep.stop()
        ui.removeCallbacks(signInTick)
        NativeLogin.onTrafficSample = null
        SetupBridge.keyboard(false)
        ui.removeCallbacks(rootCheck)
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
            (::dialogView.isInitialized && dialogView.visibility == View.VISIBLE) ||
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
        if (lastDpad?.want != true) return                              // the toolbar off: Start is the game's own wheel
        startReadAt = SystemClock.uptimeMillis()
        openPause("Start", byStart = true)
    }

    /** the pause wheel over the room on show (Start, or the bar's swirl): one route */
    private fun openPause(why: String, byStart: Boolean) {
        if (::radialView.isInitialized && radialView.isOpen) return
        if (::pauseView.isInitialized && pauseView.isOpen) return       // its own reader closes it
        if (startView.visibility == View.VISIBLE || setupView.visibility == View.VISIBLE || loginPrompt.isOpen) return
        Log.i("WCHOME", "pause: the wheel over ${room.name} ($why)")
        // a reverse still playing: stopped, and the open goes on from where the panels are (no jump)
        val from = if (pauseBack?.isRunning == true) slideT else 0f
        pauseBack?.cancel(); pauseBack = null
        pauseView.open(from, byStart)
    }
    /** the reverse running (a Start meanwhile cancels it and the open goes on from [slideT]) */
    private var pauseBack: android.animation.ValueAnimator? = null
    private var slideT = 0f

    /** Home's panels, the bar and the room on show, at [t] of the pause wheel's open (0 = at rest) */
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
        val bar = UiKit.pauseOut(UiKit.PAUSE_BAR, t, android.graphics.RectF(0f, 0f, pauseView.width.toFloat(), pauseView.height.toFloat()))
        strip.translationY = bar[1]
    }

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
    private fun homeShot(): android.graphics.Bitmap? = try {
        val w = homeView.width; val h = homeView.height
        if (w <= 0 || h <= 0) null else {
            val b = chatGlideBmp?.takeIf { it.width == w && it.height == h && !it.isRecycled }
                ?: android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888).also { chatGlideBmp = it }
            b.eraseColor(0)
            homeView.draw(android.graphics.Canvas(b))
            b
        }
    } catch (t: Throwable) { Log.i("WCHOME", "chat glide: no picture of Home (${t.message})"); null }

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
        s.phase == HomeLogin.Phase.AUTH -> StartView.Login.NONE to "Enter the authenticator code on the game screen."
        s.phase == HomeLogin.Phase.FAILED -> StartView.Login.NONE to "Sign-in failed. Enter it on the game screen."
        !HomeLoginStore.has(this) -> StartView.Login.FIRST to "Save it once and the game signs in by itself."
        NativeLogin.firstReadAt > 0L && NativeLogin.active -> StartView.Login.NONE to "Signing in…"
        // the file's 20 s are up and character select has not come: a code asked, or the sign-in failed
        NativeLogin.firstReadAt > 0L -> StartView.Login.NONE to "Not signed in yet? Enter it on the game screen."
        else -> null
    }
    private var loginRevealed = false

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
    /** characters whose stored choice this run already re-applied to a game whose add-on had no answer (once per run) */
    private val dpadApplied = HashSet<String>()

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
        if (d.want != null && !owed) { if (!prefs.contains(key) || prefs.getBoolean(key, false) != d.want) prefs.edit().putBoolean(key, d.want).apply(); return }
        if (prefs.contains(key) && !owed) {
            if (dpadApplied.add(key) && !homeView.inFight()) {
                val want = prefs.getBoolean(key, false)
                Log.i(HomeView.TAG, "dpad: apply the remembered choice $want for $key")
                spellsTap(if (want) d.colOn else d.colOff)
            }
            return
        }
        if (setupView.visibility == View.VISIBLE || (startView.visibility == View.VISIBLE && !setupLoading) || homeView.inFight() || setupWaiting != null) return
        if (!owed) prefs.edit().putBoolean("owed_$key", true).apply()
        // b133 (the user: "The actual text prompt for the dpad controls needs to be updated"; the user's pick "Pairs, 2 lines"): today's
        // DS WoW toolbar - the plain D-pad left Chat, right Map, up the radial, down the Home bar, in fights too
        val b = arrayListOf("Left: Chat     Right: Map", "Up: the radial     Down: the Home bar", "Works in fights too")
        Log.i(HomeView.TAG, "dpad: ask $key")
        val show = {
            // b139: ON by default the moment the game takes taps again, then the prompt asks to keep it
            spellsTap(d.colOn)
            setupLoadingDone()
            setupView.ask(CompanionSetup.Prompt("Keep the DS WoW toolbar on your D-pad?", b,
                "No turns it off (your own D-pad). Change it any time in the Controller room (DS tab)."), "Yes", "No",
                onYes = { prefs.edit().putBoolean(key, true).remove("owed_$key").apply(); spellsTap(d.colOn); setupView.close(); setupPadOff() },
                onNo = { prefs.edit().putBoolean(key, false).remove("owed_$key").apply(); spellsTap(d.colOff); setupView.close(); setupPadOff() })
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
        else setupShowAnyway("the game said no: ${msg.optString("why")}")
        setupView.padOn = setupHeld
        Log.i(HomeView.TAG, "dpad prompt: ${if (setupHeld) "held - A / B press it" else "hold not given (${msg.optString("why")})"}")
    }
    private fun setupPadOff() {
        setupWaiting = null
        setupLoadingDone()
        if (!setupPad) return
        setupPad = false; setupHeld = false; setupView.padOn = false
        if (!homeView.homeGuestWanted()) guestTap(GuestPad.cells(questsPad, "home", hold = false))
        Log.i(HomeView.TAG, "dpad prompt: free")
    }

    /** the Settings row: true = the router mode on (cols.on), false = fully off (cols.off); refused in a fight (one red pulse) */
    private fun dpadSet(on: Boolean) {
        val d = lastDpad ?: return
        dpadKey(d)?.let { getSharedPreferences("dpad", MODE_PRIVATE).edit().putBoolean(it, on).apply() }
        spellsTap(if (on) d.colOn else d.colOff)
    }

    /** the Controller tab's cells (spells' own rows `talents` 11-39 and `talentn` 36 / 37), in order: picks, then at most ONE action */
    private fun padTabTap(cells: List<Pair<String, Int>>): Boolean = TapBridge.taps(this, talentsPad ?: spellsPad, cells)

    /**
     * A CLEAN EXIT FIRST ([CleanExit]; the user's "Both" ruling after test20): the game's own Exit Game cell (Spells' secure gamemenu
     * row: "/quit", which writes SavedVariables), then wait for the game to go; [done] says whether the old close is still needed.
     */
    private fun cleanExit(why: String, done: (Boolean) -> Unit) {
        val route = CleanExit.route(lastAddonAt, SystemClock.elapsedRealtime(), homeView.inFight(), CleanExit.quitCol(lastGameMenu))
        if (route is CleanExit.Route.Force) { Log.i("WCSETUP", "clean exit ($why): ${route.why}: the old close"); done(true); return }
        val col = (route as CleanExit.Route.Quit).col
        val sent = TapBridge.tap(this, questsPad, GameMenuModel.ROW, col) { r -> Log.i("WCSETUP", "clean exit ($why): tap refused ($r)") }
        if (!sent) { Log.i("WCSETUP", "clean exit ($why): the Exit Game tap was not sent: the old close"); done(true); return }
        Log.i("WCSETUP", "clean exit ($why): the game's own Exit Game asked (its save is written as it exits)")
        val t0 = SystemClock.elapsedRealtime()
        val poll = object : Runnable {
            override fun run() {
                val s = (SystemClock.elapsedRealtime() - t0) / 1000
                when {
                    !LauncherHooks.gameUp() -> { Log.i("WCSETUP", "clean exit ($why): the game exited by itself after $s s"); done(false) }
                    SystemClock.elapsedRealtime() - t0 > CleanExit.WAIT_MS -> {
                        Log.i("WCSETUP", "clean exit ($why): still running after $s s: the old close"); done(true)
                    }
                    else -> ui.postDelayed(this, 500L)
                }
            }
        }
        ui.postDelayed(poll, 500L)
    }

    companion object {
        /** the router's "pause" after the app's own Start read is the same press (the doc arrives ~0.1-0.4 s later) */
        const val START_DEDUPE_MS = 800L
        const val SWEEP_ACTION = "app.dsforever.companion.KEYSWEEP"
        @Volatile private var sweepRegistered = false
        /** a holder seen with its room hidden this long is freed (the one rule for every room; see freeHidden) */
        private const val HIDDEN_GRACE = 3_000L
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
