package app.gamenative.ui.screen.wow

import android.content.Context
import com.jrees.wowcompanion.ArtHarvest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Wait for required art only; optional background work must not delay Play. */
suspend fun awaitArtHarvest(context: Context, onStatus: (String) -> Unit): Boolean {
    val h = ArtHarvest
    onStatus("Preparing your game art...")
    val listener: (ArtHarvest.State) -> Unit = { st ->
        if (st is ArtHarvest.State.Running && st.beforePlay)
            onStatus("Preparing your game art... ${(st.fraction * 100).toInt()}%")
    }
    h.onState(listener)
    try {
        val ok = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine<Boolean> { cont ->
                h.start(context) { done -> if (cont.isActive) cont.resumeWith(Result.success(done)) }
            }
        }
        if (!ok) {
            val why = (h.state as? ArtHarvest.State.Failed)?.why ?: "unknown"
            Timber.w("DS Forever art harvest: $why")
            onStatus("Couldn't prepare the game art: $why")
            return false
        }
        return withContext(Dispatchers.IO) { !h.state.blocksPlay && h.beforePlayReady(context) }
    } finally {
        h.offState(listener)
    }
}
