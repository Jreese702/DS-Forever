package com.jrees.wowcompanion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.jrees.wowcompanion.SocialModel.Act
import com.jrees.wowcompanion.SocialModel.Row
import com.jrees.wowcompanion.SocialModel.Tab
import org.json.JSONObject

/**
 * THE SOCIAL ROOM (social session), drawn to UI Art's SPEC (`wow-companion-design/social/SPEC.md`) with kit calls only.
 * The user's picks (2026-09-28): ONE room; its pages as pictures down the right edge (Friends, Guild, Raid);
 * select a player, then ONE action (Whisper) and More (the game's player menu over the right-hand pane,
 * what cannot be undone red and asked first, naming the player); a raid of forty as eight groups of five, the leader
 * drags a member to another group. B that leaves the room goes HOME (user, 23:12).
 * No Who page (the user's answer 2026-09-30: "Only in Group Finder, like the game"): Who is the Group Finder room's tab 3.
 *
 * The data is [SocialModel] (the addon's `social` documents). Every game action is ONE tap on the shared pad, planned by
 * the model: a page touch = a view tap, a line touch = a pick tap (neither is a game action), an action = one act tap,
 * sent only when the addon's pick names the chosen player. When the pick has gone stale, the one touch sends a fresh pick
 * first and the act as soon as the addon names the player (the pick is no game action: the lock's "pick before the act").
 */
class SocialView(context: Context) : View(context), BLeadsHome {

    val model = SocialModel()
    /** B that leaves the room, and the page's own way out: the activity shows Home */
    var onLeave: () -> Unit = {}
    /** Whisper: the Chat room with the name ready (the chat session's `whisperTo`); nothing is sent to the game */
    var onWhisper: (String) -> Unit = {}
    /** the chat session's `ChatNames.canWhisper`: a name typed chat cannot spell is not offered */
    var canWhisper: (String) -> Boolean = { true }

    private enum class Page(val tex: String) { FRIENDS("tab_friends"), GUILD("tab_guild"), RAID("tab_raid") }

    private var page = Page.FRIENDS
    private var ignoreOpen = false
    /** the chosen player of each list, by the name the row shows (rows re-order; names do not) */
    private val chosen = HashMap<Tab, String>()
    private var menuOpen = false
    private var confirm: Act? = null
    private var confirmLabel: String? = null
    private val scroll = HashMap<String, Float>()
    private val content = HashMap<String, Float>()

    /** an action waiting for its pick's reply: sent the moment the addon names the player (or dropped after 4 s) */
    private data class Pending(val act: Act, val label: String, val tab: Tab, val at: Long)
    private var pending: Pending? = null
    private var lastPickKey: String? = null

    // ---------------------------------------------------------------- the addon's documents

    /** a `social` or `socialack` document (MainActivity routes them here) */
    fun onDoc(msg: JSONObject) {
        val now = System.currentTimeMillis()
        if (!model.apply(msg, now)) return
        maybeOpen()
        runPending(now)
        syncPick()
        invalidate()
    }

    /** the room came on the screen: make the addon show this page's list (its rows then carry their pad columns). The cursor
     *  starts ON the page's selected tab (the user's LATER item 19: "something already highlighted because its selected ... a
     *  really great natural starting point for the cursor") */
    fun onShown() {
        sendView()
        openWanted = false                                 // a new visit: the next "can open" asks for the window
        sentBHome = null                                   // the first draw tells the addon where B leads now
        if (!menuOpen && confirm == null) focus = Fk.Tab(page.ordinal)
        invalidate()
    }

    /** the GAME opened its own window: show that page, the cursor on its tab */
    fun openPage(t: Tab) {
        closeOverlays()
        goTo(when (t) { Tab.GUILD -> Page.GUILD; Tab.RAID -> Page.RAID; else -> Page.FRIENDS })
        focus = Fk.Tab(page.ordinal)
        invalidate()
    }

    private fun send(t: SocialModel.Tap?): Boolean = t != null && TapBridge.tap(context, model.pad, t.row, t.col)

    /**
     * Home's self-heal (one rule for every room, tracking's yes under the user's delegation, 2026-09-30): the addon's `free` tap,
     * whatever this view believes. It is NO game action: Social.lua gives the shared controller capture back (the capture refuses
     * quietly when Social is not the holder) and stops asking for it again; no game window is opened or closed, nothing is pressed.
     * Home calls it when a CURRENT interact document names the holder "social" while the room is not on screen. True when a tap
     * went out; one log line either way.
     */
    fun freeAgain(): Boolean {
        val t = model.freeTap()
        val sent = send(t)
        Log.i(TAG, "free again: ${if (sent) "sent" else if (t == null) "not sent (no free code yet)" else "not sent"}")
        return sent
    }

    private fun listTab(): Tab = when (page) {
        Page.FRIENDS -> if (ignoreOpen) Tab.IGNORE else Tab.FRIENDS
        Page.GUILD -> Tab.GUILD; Page.RAID -> Tab.RAID
    }

    private var lastView: Pair<Tab, Long>? = null
    /** the view tap for this page's list (no game action), not twice for the same list inside 2 s (the reply is on its way) */
    private fun sendView() {
        val t = listTab(); val now = System.currentTimeMillis()
        val lv = lastView
        if (lv != null && lv.first == t && now - lv.second < 2000) return
        if (send(model.tabTap(t))) lastView = t to now
    }

    private fun goTo(p: Page) {
        if (p == page && !ignoreOpen) return
        page = p; ignoreOpen = false
        chosen.remove(listTab())                        // a page just opened chooses nobody (SPEC 5)
        focus = Fk.Tab(p.ordinal)                       // the cursor rests on the page's tab (LATER item 19)
        sendView()
        invalidate()
    }

    private fun chosenRow(): Row? {
        val t = listTab()
        val name = chosen[t] ?: return null
        return model.rows(t).firstOrNull { it.label == name }
    }

    private fun choose(r: Row) {
        chosen[r.tab] = r.label
        syncPick()
        invalidate()
    }

    /** the chosen line gets its pick tap once its list is the addon's shown one (no game action), once per choice */
    private fun syncPick() {
        val r = chosenRow() ?: return
        if (model.shown != r.tab || r.slot == null) return
        val key = "${r.tab}:${r.label}:${r.slot}"
        if (key == lastPickKey || model.pickMatches(r, Act.INVITE, System.currentTimeMillis())) return
        if (send(model.selectTap(r))) lastPickKey = key
    }

    private fun runPending(now: Long) {
        val p = pending ?: return
        if (now - p.at > 4000) { pending = null; return }
        val r = model.rows(p.tab).firstOrNull { it.label == p.label } ?: return
        if (model.pickMatches(r, p.act, now)) { pending = null; send(model.actTapUnchecked(p.act)) }
    }

    /** ONE game action on the chosen player (or the Chat room for Whisper) */
    private fun perform(act: Act, r: Row) {
        val now = System.currentTimeMillis()
        if (act == Act.WHISPER) { r.actName?.let { if (canWhisper(it)) onWhisper(it) }; return }
        if (act.confirm && confirm == null) {
            confirm = act; confirmLabel = r.label; menuOpen = false
            if (!model.pickMatches(r, act, now)) { lastPickKey = null; send(model.selectTap(r)) }
            focus = Fk.Yes
            invalidate(); return
        }
        confirm = null
        if (model.pickMatches(r, act, now)) send(model.actionTap(r, act, now))
        else if (send(model.selectTap(r))) pending = Pending(act, r.label, r.tab, now)
        menuOpen = false
        invalidate()
    }

    private fun roomAction(act: Act) { send(model.actionTap(null, act, System.currentTimeMillis())) }

    private fun closeOverlays() {
        menuOpen = false; confirm = null; confirmLabel = null
    }

    // ---------------------------------------------------------------- words

    private fun className(file: String?): String = when (file) {
        null -> ""
        "DEATHKNIGHT" -> "Death Knight"; "DEMONHUNTER" -> "Demon Hunter"
        else -> file.lowercase().replaceFirstChar { it.uppercase() }
    }

    private fun ago(hours: Int): String = when {
        hours < 1 -> "less than an hour ago"
        hours < 24 -> if (hours == 1) "1 hour ago" else "$hours hours ago"
        hours < 24 * 30 -> (hours / 24).let { if (it == 1) "1 day ago" else "$it days ago" }
        else -> (hours / (24 * 30)).let { if (it == 1) "1 month ago" else "$it months ago" }
    }

    private fun lastOnline(r: Row): String? {
        val t = r.lastOnline ?: return r.hoursAgo?.let { ago(it) }
        if (t <= 0) return null
        return ago(((System.currentTimeMillis() / 1000 - t) / 3600).toInt().coerceAtLeast(0))
    }

    private fun gameName(g: String?): String? = when (g) {
        null, "", "WoW" -> null
        "BSAp", "App" -> "the Battle.net app"
        else -> g
    }

    private fun status(r: Row): String = when {
        !r.online -> "offline"; r.dnd -> "dnd"; r.afk -> "away"; else -> "online"
    }

    private fun nameColour(r: Row): Int = when {
        r.bnTag != null -> Theme.battleNet
        !r.online -> Theme.offline
        else -> Theme.classColourOf(r.cls)
    }

    private fun line2(r: Row): String = when (r.tab) {
        Tab.FRIENDS -> when {
            !r.online -> "Offline"
            r.bnTag != null -> gameName(r.game)?.let { g -> listOfNotNull(r.actName?.substringBefore('-'), g).joinToString("   ") }
                ?: listOfNotNull(r.actName?.substringBefore('-'), levelClassNoRace(r), r.zone).joinToString("   ")
            else -> listOfNotNull(levelClassNoRace(r), r.zone).joinToString("   ")
        }
        Tab.GUILD -> listOfNotNull(levelClassNoRace(r), if (r.online) r.zone else r.hoursAgo?.let { "last online " + ago(it) }).joinToString("   ")
        Tab.RAID -> listOfNotNull(levelClassNoRace(r), r.zone).joinToString("   ")
        Tab.IGNORE -> ""
    }

    private fun levelClassNoRace(r: Row): String? =
        listOfNotNull(r.level?.let { "Level $it" }, r.cls?.let { className(it) }).joinToString(" ").takeIf { it.isNotEmpty() }

    /** SPEC 5: the chosen player's rows, only those the feed can deliver */
    private fun details(r: Row): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        fun add(l: String, v: String?) { if (!v.isNullOrEmpty()) out += l to v }
        val lc = listOfNotNull(r.level?.toString(), r.cls?.let { className(it) }).joinToString(" ")
        when (r.tab) {
            Tab.FRIENDS -> if (r.bnTag != null) {
                add("Character", r.actName?.substringBefore('-')); add("Level", lc.takeIf { r.online })
                add("Zone", if (r.online) (r.zone ?: gameName(r.game)) else null); add("Game", if (r.online) gameName(r.game) ?: "World of Warcraft" else null)
                add("Note", r.note); add("Last online", if (!r.online) lastOnline(r) else null)
            } else { add("Level", lc); add("Zone", if (r.online) r.zone else null); add("Note", r.note) }
            Tab.GUILD -> { add("Level", lc); add("Zone", if (r.online) r.zone else null); add("Rank", r.rankName); add("Note", r.note)
                add("Officer", r.officerNote); add("Last online", if (!r.online) lastOnline(r) else null) }
            Tab.RAID -> { add("Level", lc); add("Race", r.race); add("Zone", r.zone)
                add("Role", when (r.role) { "TANK" -> "Tank"; "HEALER" -> "Healer"; "DAMAGER" -> "Damage"; else -> null })
                add("Guild", r.guild); add("Pet", r.pet?.ifEmpty { "Yes" }) }
            Tab.IGNORE -> {}
        }
        return out
    }

    /** the ONE action: Whisper (Unignore on the ignore list), DISABLED when it cannot be done */
    private fun oneAction(r: Row): Pair<Act, Boolean> {
        val act = when (r.tab) { Tab.IGNORE -> Act.UNIGNORE; else -> Act.WHISPER }
        val allowed = act in model.actions(r) && (act != Act.WHISPER || r.actName?.let(canWhisper) == true)
        return act to allowed
    }

    private fun confirmText(act: Act, name: String): String = when (act) {
        Act.UNFRIEND -> "Remove $name from your friends list?"
        Act.IGNORE -> "Ignore $name?"
        Act.GUILD_REMOVE -> "Remove $name from the guild?"
        Act.LEADER -> "Make $name the group leader?"
        Act.UNINVITE -> "Remove $name from the group?"
        else -> "${act.word} $name?"
    }

    // ---------------------------------------------------------------- the controller's targets and the touch targets

    private sealed class Fk {
        data class Line(val label: String) : Fk()
        object More : Fk(); object Action : Fk(); object IgnoreBtn : Fk(); object Convert : Fk()
        data class Tab(val i: Int) : Fk()
        data class MenuRow(val i: Int) : Fk()
        object Yes : Fk(); object No : Fk()
    }

    private class Nav(val key: Fk, val r: RectF, val act: () -> Unit)
    private val nav = ArrayList<Nav>()
    private val hits = ArrayList<Nav>()
    private var focus: Fk? = null

    // ---------------------------------------------------------------- press feedback (the user 2026-10-07, via tracking: "all other context
    // buttons should have similar feedback"; Interact's recipe; the push itself is the kit's: UiKit.button DOWN / UiKit.pressed, art-kit
    // efa4a089): a touched button shows pushed while the finger is on it and stays pushed
    // PRESS_MIN_MS after it lifts; a controller A / X / Y (B on a confirmation's No) pushes its button in the moment the press reaches the
    // app, before the action runs (the game's answer is never waited for)
    private var pressed: Fk? = null
    private var lingerUntil = 0L
    private fun pushable(k: Fk?): Boolean = when (k) {
        is Fk.MenuRow -> true
        Fk.Action, Fk.More, Fk.Convert, Fk.Yes, Fk.IgnoreBtn -> true
        Fk.No -> confirm != null
        else -> false
    }
    private fun lingerPress(k: Fk?) {
        if (!pushable(k)) return
        pressed = k
        lingerUntil = System.currentTimeMillis() + PRESS_MIN_MS
        invalidate()
        postDelayed({ if (pressed == k && System.currentTimeMillis() >= lingerUntil) { pressed = null; invalidate() } }, PRESS_MIN_MS + 10L)
    }
    /** the button a controller key pushes now: A = the cursor's, X = the one action, Y = More, B = a confirmation's No */
    private fun padButton(k: String): Fk? = when (k) {
        "PAD1" -> focus
        "PAD3" -> if (!menuOpen && confirm == null) Fk.Action else null
        "PAD4" -> if (!menuOpen && confirm == null) Fk.More else null
        "PAD2" -> if (confirm != null) Fk.No else null
        else -> null
    }
    private fun isPushed(k: Fk): Boolean = pressed == k || padNow?.isDown(k) == true
    private fun btnState(k: Fk, enabled: Boolean = true) =
        if (!enabled) UiKit.Button.DISABLED else if (isPushed(k)) UiKit.Button.DOWN else UiKit.Button.UP

    // b117 (the user on b116: "the button push is delayed a moment after I push it, its working but it feels slow and laggy"): the relayed
    // press (onPad) arrives a game round trip after the press. The app's own controller reader (Spells' PressFeedback on the shared pad
    // reader) shows A / B / X / Y's button DOWN in the press's own frame while Social shows and holds the controller; the drawing only,
    // what the press does still comes from the relayed document.
    // nullable: the View constructor may report a visibility change before this class's own fields exist
    private var padNow: PressFeedback? = null
    init {
        padNow = PressFeedback(this).apply {
            padTarget = { k -> if (visibility != VISIBLE || !model.controllerHeld) null else padButton(k) }
        }
    }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        padNow?.setActive(isShown)
    }
    override fun onDetachedFromWindow() { padNow?.setActive(false); super.onDetachedFromWindow() }
    /** so7: the game's window was asked for on this visit; where the room's next B leads, as last told to the addon */
    private var openWanted = false
    private var sentBHome: Boolean? = null
    /** the last cursor move came from the controller: only then is the cursor's line kept in view (a touch scroll is not undone) */
    private var padSteered = false
    private var lastPadN = -1

    private fun target(key: Fk, r: RectF, act: () -> Unit) { val n = Nav(key, RectF(r), act); nav += n; hits += n }

    // ---------------------------------------------------------------- drawing

    private var listBox = RectF(); private var raidDrop: List<Pair<Int, RectF>> = emptyList()
    private val raidPlaces = HashMap<String, RectF>()

    override fun onDraw(c: Canvas) {
        nav.clear(); hits.clear()
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Theme.ink)
        UiKit.header(c, w, "Social")
        val tabsX = w - 118f
        val right = tabsX - 4f
        val wide = page == Page.RAID && model.inRaid
        val split = right * 0.62f
        if (wide) UiKit.pane(c, RectF(0f, 62f, right, h))
        else {
            UiKit.pane(c, RectF(0f, 62f, split, h))
            UiKit.pane(c, RectF(split, 62f, right, h), right = true)
            UiKit.paneDivider(c, split, 62f, h)
        }
        // the user's decision for SIDE tabs (via UI Art, after b118's gaps): "(a) No glyphs" -- the three tabs sit together as before (the
        // chosen one raised, the controller's tab lit); LB / RB still page them, with no badge and no gap
        val tabs = Page.values().indices.map { i -> RectF(tabsX, 72f + i * 128f, tabsX + 116f, 72f + i * 128f + 126f) }
        for ((i, p) in Page.values().withIndex()) {
            val r = tabs[i]
            // LATER 19 (the user's pick, 2026-10-07: "lit + the gold edge shaped to the tab"): the controller's cursor on a tab is the tab lit
            UiKit.sideTab(c, r, UiKit.tex(p.tex), p == page, cursor = focus == Fk.Tab(i))
            target(Fk.Tab(i), RectF(tabsX - 6f, r.top, w, r.bottom + 2f)) { closeOverlays(); goTo(p) }
        }
        val list = RectF(0f, 68f, split - 8f, h - 6f)
        val detail = RectF(split + 26f, 72f, right - 22f, h - 16f)
        listBox = list
        if (wide) raid(c, right, h, split)
        else {
            when (page) {
                Page.FRIENDS -> if (ignoreOpen) ignoreList(c, list) else friends(c, list)
                Page.GUILD -> guild(c, list)
                Page.RAID -> party(c, list, h)
            }
            val r = chosenRow()
            if (r != null) person(c, detail, r) else nobody(c, detail)
        }
        // the overlays take every touch and every press while they are open
        if (menuOpen) chosenRow()?.let { playerMenu(c, it, split, right, h) } ?: run { menuOpen = false }
        confirm?.let { dialog(c, it, w, h) }
        // the cursor is ALWAYS shown (the user 2026-09-30: "it should already be there"): when its target is gone it rests on
        // what is selected
        if (focus == null || nav.none { it.key == focus }) focus = restingTarget()
        // the gold box for everything but a tab (a tab lights itself up, above: never the box on a tab)
        nav.firstOrNull { it.key == focus && it.key !is Fk.Tab }?.let { UiKit.focus(c, it.r) }
        syncB()
    }

    private fun centred(c: Canvas, text: String, cx: Float, cy: Float, p: Paint) {
        p.textAlign = Paint.Align.CENTER
        c.drawText(text, cx, cy - (p.ascent() + p.descent()) / 2f, p)
    }

    private fun head(c: Canvas, r: RectF, left: String, right: String?) {
        val pt = UiKit.titlePaint(38f)
        c.drawText(left, 24f, r.top + 28f - (pt.ascent() + pt.descent()) / 2f, pt)
        if (right != null) {
            val pc = UiKit.bodyPaint(23f, Theme.dim).apply { textAlign = Paint.Align.RIGHT }
            c.drawText(right, r.right - 20f, r.top + 30f - (pc.ascent() + pc.descent()) / 2f, pc)
        }
    }

    /** the lines of a list from [top], clipped to the pane and dragged; each line chooses its player */
    private fun lines(c: Canvas, key: String, r: RectF, top: Float, rows: List<Row>,
                      word: (Row) -> String? = { null }, mark: (Row) -> String? = { null }, mark2: (Row) -> String? = { null },
                      nameMark: (Row) -> String? = { null }) {
        val clip = RectF(r.left, top, r.right, r.bottom)
        val s = scroll[key] ?: 0f
        content[key] = rows.size * 100f
        val sel = chosen[listTab()]
        // the cursor's line is kept in view when the controller moved it
        (focus as? Fk.Line)?.takeIf { padSteered }?.let { f ->
            val i = rows.indexOfFirst { it.label == f.label }
            if (i >= 0) {
                val y0 = top + i * 100f - s
                if (y0 < top) scroll[key] = i * 100f
                else if (y0 + 100f > clip.bottom) scroll[key] = (i + 1) * 100f - clip.height()
            }
        }
        val sc = scroll[key] ?: 0f
        c.save(); c.clipRect(clip)
        for ((i, row) in rows.withIndex()) {
            val y = top + i * 100f - sc
            if (y + 100f < clip.top || y > clip.bottom) continue
            val box = RectF(12f, y, r.right - 12f, y + 100f)
            UiKit.playerLine(c, box, status(row), row.label, nameColour(row), line2(row), row.label == sel,
                word = word(row), mark = mark(row), mark2 = mark2(row), nameMark = nameMark(row))
            val vis = RectF(box.left, maxOf(box.top, clip.top), box.right, minOf(box.bottom, clip.bottom))
            if (vis.height() > 20f) target(Fk.Line(row.label), vis) { choose(row) }
        }
        c.restore()
    }

    private fun menuButton(c: Canvas, r: RectF, key: Fk, down: Boolean, act: () -> Unit) {
        val cx = r.right - 64f; val cy = r.top + 36f
        UiKit.trackerButton(c, "filter", cx, cy, 76f, down || isPushed(key))
        target(key, RectF(cx - 55f, cy - 55f, cx + 55f, cy + 55f), act)
    }

    private fun friends(c: Canvas, r: RectF) {
        // FOR UI ART: the SPEC puts the Ignore menu button over the first line; the Friends page gives it a head row of its
        // own so that nothing overlaps (reported in AGENT_LOG)
        val online = model.rows(Tab.FRIENDS).count { it.online }
        val pc = UiKit.bodyPaint(25f, Theme.dim)
        c.drawText("$online online", 24f, r.top + 32f - (pc.ascent() + pc.descent()) / 2f, pc)
        menuButton(c, r, Fk.IgnoreBtn, false) { ignoreOpen = true; chosen.remove(Tab.IGNORE); focus = Fk.IgnoreBtn; sendView(); invalidate() }
        // a favourite (the game's accountInfo.isFavorite) wears the game's own star RIGHT AFTER THE NAME, as the game's friends list
        // draws it (UI Art's kit: atlas friendslist-favorite -> mark_favourite; UiKit.playerLine nameMark). No picture yet = no star.
        lines(c, "friends", r, r.top + 76f, model.rows(Tab.FRIENDS),
            nameMark = { if (it.favourite) "mark_favourite" else null })
    }

    private fun ignoreList(c: Canvas, r: RectF) {
        head(c, r, "Ignored", null)
        menuButton(c, r, Fk.IgnoreBtn, true) { ignoreOpen = false; focus = Fk.IgnoreBtn; sendView(); invalidate() }
        lines(c, "ignore", r, r.top + 76f, model.rows(Tab.IGNORE))
    }

    private fun guild(c: Canvas, r: RectF) {
        if (!model.inGuild) { message(c, r, "You are not in a guild."); return }
        val g = model.guildName ?: "Guild"
        if (model.guildHidden) {
            head(c, r, g, null)
            message(c, r, "Hidden by the game right now"); return
        }
        head(c, r, g, "${model.guildMembers} members   ${model.guildOnline} online")
        model.guildMotd?.let { motd ->
            val sl = StaticLayout.Builder.obtain(motd, 0, motd.length, TextPaint(UiKit.bodyPaint(23f, Theme.motd)), (r.width() - 60f).toInt())
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(4f, 1f).setMaxLines(2)
                .setEllipsize(android.text.TextUtils.TruncateAt.END).build()
            c.save(); c.translate(24f, r.top + 54f); sl.draw(c); c.restore()
        }
        lines(c, "guild", r, r.top + 128f, model.rows(Tab.GUILD), word = { it.rankName })
    }

    private fun message(c: Canvas, r: RectF, text: String) {
        centred(c, text, r.centerX(), r.top + 380f, UiKit.bodyPaint(26f, Theme.dim))
    }

    private fun groupMark(r: Row): String? = when {
        r.ready == "ready" -> "ready_yes"; r.ready == "notready" -> "ready_no"; r.ready == "waiting" -> "ready_waiting"
        else -> when (r.role) { "TANK" -> "role_tank"; "HEALER" -> "role_heal"; "DAMAGER" -> "role_dps"; else -> null }
    }

    private fun party(c: Canvas, r: RectF, h: Float) {
        if (model.groupKind == "none") { message(c, r, "You are not in a group."); return }
        val rows = model.rows(Tab.RAID)
        head(c, r, "Party", "${rows.size} of 5")
        lines(c, "party", r, r.top + 62f, rows, mark = { groupMark(it) },
            mark2 = { if (it.rank == 2) "mark_leader" else if (it.rank == 1) "mark_assistant" else null })
        if (Act.TO_RAID in model.roomActions(Tab.RAID)) {
            val b = RectF(r.right - 342f, h - 127f, r.right - 12f, h - 16f)
            UiKit.button(c, b, "Convert to Raid", btnState(Fk.Convert), 28f)
            target(Fk.Convert, b) { roomAction(Act.TO_RAID) }
        }
    }

    private fun nobody(c: Canvas, r: RectF) {
        if (page == Page.RAID && model.groupKind == "none") return
        if (page == Page.GUILD && (!model.inGuild || model.guildHidden)) return
        centred(c, "Select a player", r.centerX(), r.top + 300f, UiKit.bodyPaint(25f, Theme.dim))
    }

    private fun person(c: Canvas, r: RectF, row: Row) {
        var y = UiKit.playerHead(c, r, row.cls ?: "", row.label, nameColour(row).let { if (row.bnTag != null) Theme.battleNet else it })
        for ((label, value) in details(row)) y = UiKit.detailRow(c, r.left, y, r.right, label, value)
        val (act, ok) = oneAction(row)
        val actBox = RectF(r.left, r.bottom - 111f, r.right, r.bottom)
        UiKit.button(c, actBox, act.word, "rev_x", btnState(Fk.Action, ok))
        target(Fk.Action, actBox) { if (ok) perform(act, row) }
        if (row.tab != Tab.IGNORE && model.menu(row).isNotEmpty()) {
            val moreBox = RectF(r.left, r.bottom - 236f, r.right, r.bottom - 125f)
            UiKit.button(c, moreBox, "More", "rev_y", btnState(Fk.More))
            target(Fk.More, moreBox) { openMenu() }
        }
    }

    // ---------------------------------------------------------------- a raid (SPEC 6)

    private fun raidMark(r: Row): String? = when {
        r.ready == "ready" -> "ready_yes"; r.ready == "notready" -> "ready_no"; r.ready == "waiting" -> "ready_waiting"
        r.rank == 2 -> "mark_leader"; r.rank == 1 -> "mark_assistant"
        r.mainTank -> "mark_maintank"; r.mainAssist -> "mark_mainassist"
        else -> null
    }

    private fun raid(c: Canvas, right: Float, h: Float, split: Float) {
        val foot = h - 128f
        val gw = (right - 24f) / 4f
        val gh = (foot - 72f) / 2f
        val rh = (gh - 50f) / 5f
        val rows = model.rows(Tab.RAID)
        val sel = chosen[Tab.RAID]
        val drops = ArrayList<Pair<Int, RectF>>()
        raidPlaces.clear()
        for (g in 0 until 8) {
            val x = 12f + (g % 4) * gw; val y = 70f + (g / 4) * gh
            val box = RectF(x, y, x + gw, y + gh)
            UiKit.raidGroup(c, box, "Group ${g + 1}")
            drops += (g + 1) to box
            val members = rows.filter { (it.group ?: 1) == g + 1 }
            for (i in 0 until 5) {
                val place = RectF(x + 10f, y + 46f + i * rh, x + gw - 10f, y + 46f + (i + 1) * rh)
                val m = members.getOrNull(i)
                if (m == null) { UiKit.raidLine(c, place, null); continue }
                val colour = when { m.dead -> Theme.unusable; !m.online -> Theme.offline; else -> Theme.classColourOf(m.cls) }
                UiKit.raidLine(c, place, m.label, colour, m.label == sel, raidMark(m))
                raidPlaces[m.label] = RectF(place)
                target(Fk.Line(m.label), place) { choose(m) }
            }
        }
        raidDrop = drops
        dragging?.let { d -> drops.firstOrNull { it.second.contains(dragX, dragY) }?.let { UiKit.focus(c, it.second) }
            val p = UiKit.bodyPaint(26f, Theme.gold)
            c.drawText(d, dragX + 20f, dragY - 20f, p) }
        // the class-only line (the game's class buttons with counts, then Pets): left out with no pets and nobody marked
        val marked = rows.any { it.mainTank || it.mainAssist }
        if (model.pets > 0 || marked) {
            var x = 24f
            val cy = foot - 22f
            val p = UiKit.bodyPaint(22f, Theme.dim)
            for ((cls, n) in rows.groupingBy { it.cls ?: "" }.eachCount().filterKeys { it.isNotEmpty() }) {
                UiKit.classIcon(cls)?.let { c.drawBitmap(it, null, RectF(x, cy - 15f, x + 30f, cy + 15f), Paint(Paint.FILTER_BITMAP_FLAG)); x += 36f }
                c.drawText("$n", x, cy - (p.ascent() + p.descent()) / 2f, p); x += p.measureText("$n") + 22f
            }
            if (model.pets > 0) c.drawText("Pets ${model.pets}", x, cy - (p.ascent() + p.descent()) / 2f, p)
        }
        UiKit.divider(c, 0f, foot, right, foot)
        val r = chosenRow() ?: return
        r.cls?.let { cls -> UiKit.classIcon(cls)?.let { c.drawBitmap(it, null, RectF(24f, foot + 22f, 108f, foot + 106f), Paint(Paint.FILTER_BITMAP_FLAG)) } }
        val pn = UiKit.bodyPaint(32f, Theme.classColourOf(r.cls))
        c.drawText(r.label, 124f, foot + 46f - (pn.ascent() + pn.descent()) / 2f, pn)
        val p2 = UiKit.bodyPaint(23f, Theme.dim)
        c.drawText(listOfNotNull(levelClassNoRace(r), r.group?.let { "Group $it" }).joinToString("   "), 124f,
            foot + 86f - (p2.ascent() + p2.descent()) / 2f, p2)
        if (!r.me) {
            val more = RectF(right - 588f, foot + 10f, right - 338f, foot + 121f)
            if (model.menu(r).isNotEmpty()) {
                UiKit.button(c, more, "More", "rev_y", btnState(Fk.More))
                target(Fk.More, more) { openMenu() }
            }
            val (act, ok) = oneAction(r)
            val wb = RectF(right - 324f, foot + 10f, right - 24f, foot + 121f)
            UiKit.button(c, wb, act.word, "rev_x", btnState(Fk.Action, ok))
            target(Fk.Action, wb) { if (ok) perform(act, r) }
        }
    }

    // ---------------------------------------------------------------- More: the game's player menu (SPEC 5)

    private var menuSel = 0

    private fun openMenu() {
        val r = chosenRow() ?: return
        if (model.menu(r).isEmpty()) return
        menuOpen = true; menuSel = 0; focus = Fk.MenuRow(0)
        invalidate()
    }

    private fun playerMenu(c: Canvas, r: Row, split: Float, right: Float, h: Float) {
        nav.clear(); hits.clear()
        val acts = model.menu(r)
        val pf = Paint().apply { color = Color.rgb(18, 14, 9) }
        c.drawRect(split + 2f, 62f, right, h, pf)
        val box = RectF(split + 8f, 70f, right - 6f, 70f + 96f + acts.size * 112f + 24f + 70f)
        val inner = UiKit.menu(c, box, solid = true)
        r.cls?.let { cls -> UiKit.classIcon(cls)?.let { c.drawBitmap(it, null, RectF(box.left + 24f, box.top + 18f, box.left + 88f, box.top + 82f), Paint(Paint.FILTER_BITMAP_FLAG)) } }
        val pn = UiKit.bodyPaint(32f, nameColour(r))
        while (pn.textSize > 24f && pn.measureText(r.label) > box.right - 20f - (box.left + 104f)) pn.textSize -= 1f
        c.drawText(android.text.TextUtils.ellipsize(r.label, TextPaint(pn), box.right - 20f - (box.left + 104f), android.text.TextUtils.TruncateAt.END).toString(),
            box.left + 104f, box.top + 38f - (pn.ascent() + pn.descent()) / 2f, pn)
        val p2 = UiKit.bodyPaint(23f, Theme.dim)
        c.drawText(levelClassNoRace(r) ?: "", box.left + 104f, box.top + 72f - (p2.ascent() + p2.descent()) / 2f, p2)
        UiKit.divider(c, box.left + 20f, box.top + 95f, box.right - 20f, box.top + 95f)
        for ((i, act) in acts.withIndex()) {
            val row = RectF(inner.left, box.top + 102f + i * 112f, inner.right, box.top + 102f + (i + 1) * 112f)
            val ok = act != Act.WHISPER || r.actName?.let(canWhisper) == true
            UiKit.pressed(c, row, ok && isPushed(Fk.MenuRow(i))) {
                UiKit.menuRow(c, row, act.word, (focus as? Fk.MenuRow)?.i == i, if (act.confirm) Theme.unusable else Theme.text, enabled = ok)
            }
            val n = Nav(Fk.MenuRow(i), row) { if (ok) { menuOpen = false; perform(act, r) } }
            nav += n; hits += n
        }
        UiKit.hints(c, box.right - 28f, box.bottom - 56f, "rev_a" to "Choose", "rev_b" to "Back")
        // a tap outside the box closes the menu
        hits.add(0, Nav(Fk.No, RectF(0f, 0f, width.toFloat(), height.toFloat())) { menuOpen = false; focus = Fk.More; invalidate() })
    }

    // ---------------------------------------------------------------- the confirmation (the game's dialog, naming the player)

    private fun dialog(c: Canvas, act: Act, w: Float, h: Float) {
        nav.clear(); hits.clear()
        val now = System.currentTimeMillis()
        val r = model.rows(listTab()).firstOrNull { it.label == confirmLabel }
        // the name the addon itself resolved; until it answers, the row's own name, and Yes waits
        val sure = r != null && model.pickMatches(r, act, now)
        val name = if (sure) (model.pickedTag ?: model.pickedName ?: r!!.label) else (confirmLabel ?: "")
        UiKit.cover(c, RectF(0f, 0f, w, h))
        val text = confirmText(act, name)
        val tp = TextPaint(UiKit.bodyPaint(42f, Color.WHITE))
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, tp, (1040f - 140f).toInt()).setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setLineSpacing(14f, 1f).build()
        val boxH = sl.height + 40f + 111f + 2 * 50f + 36f
        val box = RectF(w / 2f - 520f, maxOf(30f, h / 2f - boxH / 2f), w / 2f + 520f, maxOf(30f, h / 2f - boxH / 2f) + boxH)
        val inner = UiKit.dialog(c, box)
        c.save(); c.translate(box.left + 70f, inner.top + 10f); sl.draw(c); c.restore()
        val by = inner.top + 10f + sl.height + 40f
        val yes = RectF(w / 2f - 14f - 380f, by, w / 2f - 14f, by + 111f)
        val no = RectF(w / 2f + 14f, by, w / 2f + 14f + 380f, by + 111f)
        UiKit.button(c, yes, "Yes", "rev_a", btnState(Fk.Yes, sure))
        UiKit.button(c, no, "No", "rev_b", btnState(Fk.No))
        val ny = Nav(Fk.Yes, yes) { if (r != null && sure) perform(act, r) }
        val nn = Nav(Fk.No, no) { confirm = null; confirmLabel = null; invalidate() }
        nav += ny; nav += nn; hits += ny; hits += nn
        if (!sure) postInvalidateDelayed(300)
    }

    // ---------------------------------------------------------------- touch

    private var downX = 0f; private var downY = 0f; private var lastY = 0f
    private var moved = false
    private var scrollKey: String? = null
    private var dragging: String? = null
    private var dragX = 0f; private var dragY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun paneKey(x: Float, y: Float): String? = when {
        menuOpen || confirm != null -> null
        page == Page.RAID && model.inRaid -> null
        !listBox.contains(x, y) -> null
        page == Page.FRIENDS -> if (ignoreOpen) "ignore" else "friends"
        page == Page.GUILD -> "guild"; else -> "party"
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastY = e.y; moved = false; dragging = null
                scrollKey = paneKey(e.x, e.y)
                padSteered = false
                // the button under the finger shows pushed while it is held
                hits.lastOrNull { it.r.contains(e.x, e.y) }?.key?.takeIf { pushable(it) }?.let { pressed = it; lingerUntil = 0L; invalidate() }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!moved && (Math.abs(e.y - downY) > slop || Math.abs(e.x - downX) > slop)) {
                    moved = true
                    if (pressed != null) { pressed = null; invalidate() }        // a drag is no press
                    // a leader's drag of a raid member onto another group (one drag = one game action)
                    if (page == Page.RAID && model.inRaid && !menuOpen && confirm == null) {
                        val who = raidPlaces.entries.firstOrNull { it.value.contains(downX, downY) }?.key
                        val row = who?.let { n -> model.rows(Tab.RAID).firstOrNull { it.label == n } }
                        if (row != null && model.canMove(row)) dragging = row.label
                    }
                }
                if (dragging != null) { dragX = e.x; dragY = e.y; invalidate() }
                else if (moved && scrollKey != null) {
                    val k = scrollKey!!
                    val view = listBox.height() - 80f
                    scroll[k] = ((scroll[k] ?: 0f) + (lastY - e.y)).coerceIn(0f, maxOf(0f, (content[k] ?: 0f) - view))
                    invalidate()
                }
                lastY = e.y
            }
            MotionEvent.ACTION_UP -> {
                val d = dragging
                if (d != null) {
                    dragging = null
                    val g = raidDrop.firstOrNull { it.second.contains(e.x, e.y) }?.first
                    val row = model.rows(Tab.RAID).firstOrNull { it.label == d }
                    if (g != null && row != null && g != row.group) { chosen[Tab.RAID] = row.label; perform(Act.moveTo(g), row) }
                    invalidate()
                } else if (!moved) hits.lastOrNull { it.r.contains(e.x, e.y) }?.let { hit ->
                    // the cursor goes to what was touched, unless the touch put it somewhere itself (a menu, a dialog, a page)
                    val before = focus
                    lingerPress(hit.key)                      // stays pushed PRESS_MIN_MS after the finger lifts
                    hit.act()
                    if (focus == before && hit.key != Fk.No) focus = hit.key
                }
                if (pressed != null && System.currentTimeMillis() >= lingerUntil) pressed = null    // a press that did not land on its button
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { pressed = null; dragging = null; invalidate() }
        }
        return true
    }

    // ---------------------------------------------------------------- the controller (SPEC 7)

    /** a press the addon relayed ({"type":"socialpad"}); only while this room is shown (MainActivity checks) */
    fun onPad(msg: JSONObject) {
        val n = msg.optInt("n", -1)
        if (n == lastPadN) return                           // the same press seen twice
        lastPadN = n
        if (msg.has("lost")) { invalidate(); return }
        val k = msg.optString("k")
        // so7: B closed the game's own window (the capture's binding clicked its close) and the addon let go with "B": Home
        if (k == "BACK") { onLeave(); return }
        // so12: LB / RB = the previous / next page (the Group Finder and Professions rooms' rule: no wrap, not while the menu or a
        // confirmation is open); the cursor lands on the new page's tab
        if (k == "PADLSHOULDER" || k == "PADRSHOULDER") {
            if (!menuOpen && confirm == null) {
                val to = page.ordinal + if (k == "PADLSHOULDER") -1 else 1
                if (to in Page.values().indices) { goTo(Page.values()[to]); padSteered = true }
            }
            invalidate(); return
        }
        // the cursor is already shown, so every press acts (no "first press only shows the cursor": the user 2026-09-30)
        if (focus == null || nav.none { it.key == focus }) focus = restingTarget()
        padSteered = true
        padButton(k)?.let { lingerPress(it) }
        when (k) {
            "PADDUP" -> move(0, -1); "PADDDOWN" -> move(0, 1); "PADDLEFT" -> move(-1, 0); "PADDRIGHT" -> move(1, 0)
            "PAD1" -> nav.firstOrNull { it.key == focus }?.act?.invoke()                                   // A
            "PAD2" -> back()                                                                                // B
            "PAD3" -> if (!menuOpen && confirm == null)                                                    // X = the ONE action
                chosenRow()?.let { r -> oneAction(r).let { (a, ok) -> if (ok) perform(a, r) } }
            "PAD4" -> if (!menuOpen && confirm == null) openMenu()                                         // Y = More
        }
        invalidate()
    }

    /** what is already selected: the dialog's Yes, the menu's first row, the chosen player, else the page's tab */
    private fun firstTarget(): Fk? = when {
        confirm != null -> Fk.Yes
        menuOpen -> Fk.MenuRow(0)
        else -> chosenRow()?.let { Fk.Line(it.label) } ?: Fk.Tab(page.ordinal)
    }

    /** where the cursor rests when its target is gone: what is selected if it is on screen, else the page's tab, else anything */
    private fun restingTarget(): Fk? {
        val want = firstTarget()
        val tab = Fk.Tab(page.ordinal)
        return when {
            want != null && nav.any { it.key == want } -> want
            nav.any { it.key == tab } -> tab
            else -> nav.firstOrNull()?.key
        }
    }

    /**
     * so7 / so8: open the game's own Friends window behind the room when the addon says it can (the room holds the controller, no
     * Social window is shown, its `wopen` cell is armed), once each time that becomes true: on entering, and again after a fight
     * froze the cell (it is re-armed at the fight's end). A window that did not open is not asked for again until it can again.
     * The addon's cell is the guard: it never toggles an open window shut.
     */
    private fun maybeOpen() {
        val can = visibility == VISIBLE && model.controllerHeld && !model.windowShown && model.openArmed
        if (can && !openWanted) send(model.openTap())
        openWanted = can
    }

    /** so7: B leads Home when nothing inside the room is left to step back from (the same order as back() below) */
    /** RC3: the app goes Home on the B press when the next B leaves the room (Social's own chain: [bHome]) */
    override fun bLeadsHome(): Boolean = bHome()

    private fun bHome(): Boolean = confirm == null && !menuOpen && !ignoreOpen && chosen[listTab()] == null

    /** tell the addon where B leads (no game action): while it leads Home, B closes the game's window through the capture */
    private fun syncB() {
        val home = bHome()
        if (home == sentBHome) return
        if (send(model.bTap(home))) sentBHome = home
    }

    /** B: the dialog, the menu, the ignore list, the chosen player; the last B leaves the room for HOME */
    private fun back() {
        when {
            confirm != null -> { confirm = null; confirmLabel = null; focus = Fk.More }
            menuOpen -> { menuOpen = false; focus = Fk.More }
            ignoreOpen -> { ignoreOpen = false; focus = Fk.IgnoreBtn; sendView() }
            chosen[listTab()] != null -> { focus = chosenRow()?.let { Fk.Line(it.label) }; chosen.remove(listTab()) }
            else -> onLeave()
        }
    }

    /** the glow moves to the nearest target that way; on the side tabs, up / down changes the page at once, and a move that lands on a
     *  tab opens that tab (b117, the user's rule for every room's tabs) */
    private fun move(dx: Int, dy: Int) {
        val f = focus ?: return
        val cur = nav.firstOrNull { it.key == f } ?: return
        if (f is Fk.Tab && dy != 0 && !menuOpen && confirm == null) {
            val i = (f.i + dy).coerceIn(0, Page.values().size - 1)
            goTo(Page.values()[i]); focus = Fk.Tab(i); return
        }
        val cx = cur.r.centerX(); val cy = cur.r.centerY()
        var best: Nav? = null; var bestD = Float.MAX_VALUE
        for (n in nav) {
            if (n.key == f) continue
            val ex = n.r.centerX() - cx; val ey = n.r.centerY() - cy
            val along = if (dx != 0) ex * dx else ey * dy
            if (along <= 4f) continue
            val across = if (dx != 0) Math.abs(ey) else Math.abs(ex)
            val d = along + across * 2.5f
            if (d < bestD) { bestD = d; best = n }
        }
        best?.let { focus = it.key }
        // b117, the user: "the character tabs auto open to the tab that the cursor is on- I REALLY like this and want to make the other
        // tabs consistent with this behavior": a move that LANDS on a tab opens its page at once (no A); the cursor stays on the tab.
        // Landing on the page already shown changes nothing (an open ignore list stays open).
        (focus as? Fk.Tab)?.let { t ->
            if (t.i != page.ordinal && !menuOpen && confirm == null) { goTo(Page.values()[t.i]); focus = Fk.Tab(t.i) }
        }
    }

    companion object {
        private const val TAG = "WCSOCIAL"
        const val PRESS_MIN_MS = 160L         // a press stays visibly DOWN at least this long (Interact's PRESS_MIN_MS)
    }
}
