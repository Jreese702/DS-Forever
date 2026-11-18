package app.gamenative.ui.screen.wow.auth

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jrees.wowcompanion.fork.DsForeverScreen

/**
 * WHERE BROWSER LOGIN SHOWS (the user, 2026-11-18: "open Blizzard's sign-in page on the Thor's BOTTOM screen (touch + keyboard there;
 * top stays the game)"). Each state of [BattleNetBrowserLogin.ui] other than Idle is one page ([page]): Blizzard's sign-in page, or the
 * sign-in's status with Cancel / Close. With a bottom screen up (DsForeverScreen's Presentation, or the Swap screens activity) the page
 * goes there ([BottomPages]); without one (a single-screen device, the launcher in the background) upstream's full-screen dialog on
 * this screen holds the same page. Main thread.
 */
@Composable
internal fun BattleNetBrowserLoginHost() {
    val state by BattleNetBrowserLogin.ui.collectAsStateWithLifecycle()
    var onBottom by remember { mutableStateOf(false) }
    DisposableEffect(state) {
        onBottom = BottomPages.show(state)
        onDispose { }
    }
    if (onBottom || state === BattleNetBrowserLogin.Ui.Idle) return
    val current = state
    Dialog(
        onDismissRequest = { BottomPages.dismiss(current) },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { ctx -> BottomPages.page(ctx, current) },
                onRelease = { v -> (v as? BattleNetSignInPage)?.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** the pages for browser login's states, and the bottom screen's copy of the current one */
internal object BottomPages {
    /** the sign-in pages made for the bottom screen (a new bottom window makes one again): destroyed when the state moves on */
    private val made = ArrayList<BattleNetSignInPage>()

    /** [state]'s page for [ctx] (the bottom window's own context, or the dialog's) */
    fun page(ctx: Context, state: BattleNetBrowserLogin.Ui): View = when (state) {
        is BattleNetBrowserLogin.Ui.Browser -> BattleNetSignInPage(
            ctx, state.url,
            onTicket = { BattleNetBrowserLogin.submit(state, it) },
            onCancel = { BattleNetBrowserLogin.abort(state) },
            onError = { BattleNetBrowserLogin.webError(state, it) },
        )
        is BattleNetBrowserLogin.Ui.Failed -> BattleNetStatusPage(ctx, state.text, failed = true) { dismiss(state) }
        is BattleNetBrowserLogin.Ui.Waiting -> BattleNetStatusPage(ctx, state.text, failed = false) { dismiss(state) }
        BattleNetBrowserLogin.Ui.Idle -> View(ctx)
    }

    /** the page's button / Back: Cancel stops the attempt (and the game it would start), Close clears a failure */
    fun dismiss(state: BattleNetBrowserLogin.Ui) {
        if (state is BattleNetBrowserLogin.Ui.Failed) BattleNetBrowserLogin.cancel() else BattleNetBrowserLogin.abort(state)
    }

    /**
     * [state] on the bottom screen: true = it is there (or Idle: nothing to show anywhere); false = no bottom screen now, the caller
     * shows it on this screen. The previous state's sign-in page is destroyed.
     */
    fun show(state: BattleNetBrowserLogin.Ui): Boolean {
        val screen = DsForeverScreen.current
        clear()
        if (state === BattleNetBrowserLogin.Ui.Idle) { screen?.browserPage(null); return true }
        if (screen == null || !screen.bottomUp) { screen?.browserPage(null); return false }
        return screen.browserPage { ctx -> page(ctx, state).also { v -> if (v is BattleNetSignInPage) track(v) } }
    }

    /** a bottom window made this sign-in page: the one it replaces (the window was made again) goes */
    private fun track(p: BattleNetSignInPage) {
        made.toList().forEach { it.destroy() }
        made.clear(); made += p
    }

    private fun clear() { made.forEach { it.destroy() }; made.clear() }
}
