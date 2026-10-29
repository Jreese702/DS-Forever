package com.jrees.wowcompanion.stream

import com.jrees.wowcompanion.SetupFlow

/**
 * THE CURTAIN OVER THE STREAM (the user, 2026-10-28: "when it starts sometimes I see my desktop, and I don't want to see any of that,
 * just the game when it loads"). While Streaming starts, the top screen keeps the loading reveal and its centred box over the picture
 * until Desktop says WoW is SIGNED IN (character select or the world) AND in front; then the stream shows. An older Desktop that does
 * not say the screen: WoW running and (when it says) focused.
 *
 * The one exception (the user's ruling "Show its sign-in on the Thor"): while the PC's Battle.net is signed out, or Desktop's one retry
 * is spent and the sign-in needs the player, the curtain lifts so the player can sign in on the PC through the stream (the stream's
 * keyboard; DS Forever never sees, logs or stores what is typed), with one small gold line; then the curtain comes back until WoW is in.
 */
object StreamCurtain {

    sealed class Shows {
        /** the curtain: the box's rows and its gold status line */
        data class Curtain(val rows: List<SetupFlow.Check>, val line: String) : Shows()
        /** the stream, with [line] small at the top (Battle.net's sign-in) */
        data class SignIn(val line: String) : Shows()
        /** the stream itself */
        object Stream : Shows()
    }

    const val SIGN_IN = "Sign in to Battle.net"
    const val LOOKING = "Looking for your PC"
    const val NEEDS_YOU = "Sign-in needs you on the PC's Battle.net"

    /**
     * [connected] = the stream is up (Moonlight's picture arrived); [bridged] = this PC has DS Forever Desktop's bridge (Sunshine
     * alone says nothing about WoW: the stream shows as before); [pc] = Desktop's last word, null = none yet; [dots] = 0..3 for
     * "Loading" while nothing more precise is known.
     */
    fun decide(connected: Boolean, bridged: Boolean, pc: PcWow?, dots: Int = 3, route: String = "", problem: String? = null): Shows {
        // "PC found" only once the PC ANSWERED (the bridge's welcome: [pc] set; Sunshine alone: the stream up), with the way it was
        // reached ([route]: "home Wi-Fi" / "Tailscale"); before that "Looking for your PC" (party3 said "PC found" while the PC never
        // got a connection: the Thor's Tailscale was off); [problem] = why no address answered (Route.unreachable)
        val found = if (route.isEmpty()) "PC found" else "PC found ($route)"
        if (!bridged) return if (connected) Shows.Stream
            else curtain(listOf(row(LOOKING, false)), problem ?: "Connecting to your PC")
        if (pc == null) return if (connected) curtain(listOf(row(found, true)), loading(dots))      // the stream answered, Desktop not yet
            else curtain(listOf(row(LOOKING, false)), problem ?: "Connecting to your PC")
        if (pc.bnet == "signin_needed") return Shows.SignIn(SIGN_IN)
        if (pc.needsYou) return Shows.SignIn(NEEDS_YOU)
        val known = pc.screen.isNotEmpty()
        val open = if (known) pc.signedIn && pc.focused == true else pc.state == "running" && pc.focused != false
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
