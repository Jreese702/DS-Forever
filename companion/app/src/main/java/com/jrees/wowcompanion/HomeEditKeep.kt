package com.jrees.wowcompanion

/**
 * EDIT HOME'S LIVE SESSION, KEPT FOR A HOST MADE AGAIN (the bottom screen comes straight back, round 2 of the review, 2026-11-26: "Edit
 * Home with an unsaved draft ... lost on every bottom-grid return"; BottomSync.HostUi): [model] the pure state machine (the draft, the
 * cursor, the lift, the menus, the tab, the record Discard puts back), [owner] the record it was read under (it is saved only there),
 * [persisted] the draft as last written (1518's autosave base), [late] a pending name's key that came meanwhile. HomeView.editKeep
 * makes it, HomeView.editRestore takes it back. Pure data: no view, no context (HomeEditModel has no Android types).
 */
class HomeEditKeep(
    val model: HomeEditModel,
    val owner: HomeLayoutStore.Owner?,
    val persisted: HomeLayout?,
    val late: HomeLayoutStore.Late?,
)
