package com.jrees.wowcompanion.stream

import com.jrees.wowcompanion.SetupFlow

/**
 * THE CURTAIN OVER THE STREAM (the user, 2026-10-28: "when it starts sometimes I see my desktop, and I don't want to see any of that,
 * just the game when it loads"). While Streaming starts, the top screen keeps the loading reveal and its centred box over the picture
 * until Desktop says WoW's window is open (its login screen or later); then the stream shows. An older Desktop that does not say the
 * screen: WoW running.
 *
 * The one exception (the user's ruling "Show its sign-in on the Thor"): while the PC's Battle.net is signed out, or Desktop's one retry
 * is spent and the sign-in needs the player, the curtain lifts so the player can sign in on the PC through the stream (the stream's
 * keyboard; DS Forever never sees, logs or stores what is typed), with one small gold line; then the curtain comes back until WoW is in.
 *
 * WoW can stop before character select and wait for the player (the user, 2026-10-30, its gameplay rules after an update: "I should see
 * wow on my game now"), so WoW in front and still at its login steps after [LOGIN_SHOW_MS] shows ([atLoginLong]).
 *
 * Once shown it stays ([seen], the user, 2026-10-30: "When something else takes the screen focus it hides wow completely"): losing the
 * front never brings the curtain back; WoW closing, a crash or Battle.net needing the player still do.
 */
object StreamCurtain {

    sealed class Shows {
        /** the curtain: the box's rows and its gold status line */
        data class Curtain(val rows: List<SetupFlow.Check>, val line: String) : Shows()
        /** ONLY Battle.net's window out of the stream ([show]: x, y, w, h as shares of the PC's screen), black round it, with [line] small
         *  at the top (the user, 2026-10-30: "I kind of expected a simple sign in window for bnet to appear on the top screen. I dont
         *  really want to see my desktop screen for any reason until the game is launched and logging in") */
        data class SignIn(val line: String, val show: List<Float>) : Shows()
        /** the stream itself */
        object Stream : Shows()
    }

    const val SIGN_IN = "Sign in to Battle.net"
    const val SIGNING_IN = "Signing in to Battle.net"
    const val LOOKING = "Looking for your PC"
    const val NEEDS_YOU = "Sign-in needs you on the PC's Battle.net"
    /** WoW crashed on the PC because its files are older than the game's data (the user's PC 2026-10-29: a black stream behind it) */
    const val UPDATE = "Update WoW in Battle.net"
    const val CRASHED = "WoW crashed on your PC"
    const val LOGIN_SHOW_MS = 10_000L

    /** WoW is up and between its login screen and character select */
    fun atLogin(pc: PcWow?) = pc != null && (pc.screen == "login" || pc.screen == "signing_in")

    /**
     * [connected] = the stream is up (Moonlight's picture arrived); [bridged] = this PC has DS Forever Desktop's bridge (Sunshine
     * alone says nothing about WoW: the stream shows as before); [pc] = Desktop's last word, null = none yet; [dots] = 0..3 for
     * "Loading" while nothing more precise is known.
     */
    fun decide(connected: Boolean, bridged: Boolean, pc: PcWow?, dots: Int = 3, route: String = "", problem: String? = null,
               signingIn: Boolean = false, atLoginLong: Boolean = false, seen: Boolean = false): Shows {
        // "PC found" only once the PC ANSWERED (the bridge's welcome: [pc] set; Sunshine alone: the stream up), with the way it was
        // reached ([route]: "home Wi-Fi" / "Tailscale"); before that "Looking for your PC" (party3 said "PC found" while the PC never
        // got a connection: the Thor's Tailscale was off); [problem] = why no address answered (Route.unreachable)
        val found = if (route.isEmpty()) "PC found" else "PC found ($route)"
        if (!bridged) return if (connected) Shows.Stream
            else curtain(listOf(row(LOOKING, false)), problem ?: "Connecting to your PC")
        if (pc == null) return if (connected) curtain(listOf(row(found, true)), loading(dots))      // the stream answered, Desktop not yet
            else curtain(listOf(row(LOOKING, false)), problem ?: "Connecting to your PC")
        // WoW itself is up and refused the sign-in: the whole of WoW, its own message and its buttons (the user, 2026-10-30: "why doesn't
        // it show the full wow window when it shows this login error?"), not Battle.net's window
        if (connected && atLogin(pc) && pc.loginError.isNotEmpty() && pc.needsYou && pc.crash.isEmpty() && pc.bnet != "signin_needed")
            return Shows.Stream
        // the player must use Battle.net (its sign-in; an update after a crash; a sign-in that needs them): its window alone, zoomed, once
        // Desktop says where it is; until then the curtain with the same line (never the desktop)
        val bnetLine = when {
            pc.crash == "update" -> UPDATE
            pc.crash.isNotEmpty() -> CRASHED
            pc.bnet == "signin_needed" -> SIGN_IN
            pc.needsYou -> NEEDS_YOU
            else -> null
        }
        // the saved login is going in ([StreamSignIn]): nothing to see or do
        if (bnetLine != null && signingIn) return curtain(listOf(row(found, true), row("Battle.net", false)), SIGNING_IN)
        if (bnetLine != null) return if (connected && pc.show.size == 4) Shows.SignIn(bnetLine, pc.show)
            else curtain(listOf(row(found, true), row("Battle.net", false)), bnetLine)
        val known = pc.screen.isNotEmpty()
        // WoW's window is open: it shows, its login too (the user 2026-11-01: "we shouldn't hide the wow screen once its open")
        val open = if (known) pc.signedIn || atLogin(pc) else pc.state == "running"
        if (connected && open) return Shows.Stream
        val bnetOk = pc.bnet.isEmpty() || pc.bnet == "signed_in" || pc.bnet == "unknown"
        val running = pc.state == "running"
        val rows = buildList {
            add(row(found, true))
            if (pc.bnet.isNotEmpty() && pc.bnet != "unknown") add(row("Battle.net", bnetOk))
            add(row("WoW starting", running))
            if (known) add(SetupFlow.Check("Signing in", when {
                pc.signedIn -> SetupFlow.Mark.OK
                pc.loginError.isNotEmpty() -> SetupFlow.Mark.BAD
                else -> SetupFlow.Mark.WAIT
            }))
        }
        val line = when {
            !connected -> "Connecting to your PC"
            pc.bnet == "not_running" || pc.bnet == "starting" -> "Starting Battle.net on your PC"
            pc.state == "start_failed" -> "WoW didn't start on your PC"
            !running || pc.screen == "starting" || pc.screen == "none" -> "Starting WoW on your PC"
            pc.screen == "login" || pc.screen == "signing_in" -> "Signing in"
            pc.signedIn || (!known && running) -> "Bringing WoW to the front"
            else -> loading(dots)
        }
        return curtain(rows, line)
    }

    private fun row(label: String, ok: Boolean) = SetupFlow.Check(label, if (ok) SetupFlow.Mark.OK else SetupFlow.Mark.WAIT)
    private fun curtain(rows: List<SetupFlow.Check>, line: String) = Shows.Curtain(rows, line)
    private fun loading(dots: Int) = "Loading" + ".".repeat(dots.coerceIn(0, 3))
}
