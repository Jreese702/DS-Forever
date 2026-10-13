package com.jrees.wowcompanion

/**
 * WHETHER THIS PROCESS MAY ASK FOR ROOT (Home, 2026-10-08, fork phase 2). DS Forever's own app uses Magisk su for its taps, keys,
 * controller reader and private reads. Inside the private fork ("DS Forever Launcher", app.dsforever.launcher) the same views run in
 * the LAUNCHER's process, where root is never asked for (no Magisk prompt for that app; the point of the fork is no root): the fork's
 * glue sets [allowed] = false before it builds anything, and the root entry points (LogTail, PadInput, RootShell) then stay on plain
 * IO or do nothing.
 */
object RootPolicy {
    @Volatile var allowed = true
}
