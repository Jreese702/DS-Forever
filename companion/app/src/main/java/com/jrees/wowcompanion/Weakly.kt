package com.jrees.wowcompanion

/**
 * A STATIC LISTENER THAT NEVER KEEPS ITS OWNER (the 1528 test build's blocker, 2026-11-24): the settings' listener lists (HomeSettings,
 * HomeLayoutStore, GameUi, Settings, ChatFilter, TalkKeep, ItemCd) live as long as the process and never let go of a listener. A view
 * that registered `{ post { ... } }` was kept by it for good - with its map, its decoded art, every bitmap it held - and every later
 * change queued one more runnable on it (HomeViewsReleasedTest). [f] runs with [owner] while it lives; once the owner is collected the
 * listener does nothing. [f] must reach the owner only through its argument (a lambda that names the owner's members directly holds the
 * owner again: the test catches that).
 */
internal fun <T : Any> weakly(owner: T, f: (T) -> Unit): () -> Unit {
    val ref = java.lang.ref.WeakReference(owner)
    return { ref.get()?.let(f) }
}
