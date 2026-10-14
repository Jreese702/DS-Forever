package com.jrees.wowcompanion

/**
 * DS FOREVER ON THE SD CARD (p-sd-card-install, a player's report 2026-10-14: "It default installed on the SD card which means it cant
 * make a container ... got it moved to internal storage and it works"). The manifest says internalOnly, but a device setting ("force
 * allow apps on external", adoptable storage) can still put it there. Pure: the launcher passes its ApplicationInfo's fields.
 */
object InstallCheck {
    /** ApplicationInfo.FLAG_EXTERNAL_STORAGE */
    const val FLAG_EXTERNAL_STORAGE = 0x40000

    const val TITLE = "Move DS Forever to internal storage"
    const val TEXT = "It is installed on the SD card, where the game's container can't be made. " +
        "Settings > Apps > DS Forever > Storage > Change > Internal."

    /** null = installed on internal storage; else where it is (for the log) */
    fun external(flags: Int, onDefaultVolume: Boolean, sourceDir: String, dataDir: String): String? = when {
        flags and FLAG_EXTERNAL_STORAGE != 0 -> "external storage (FLAG_EXTERNAL_STORAGE)"
        sourceDir.startsWith("/mnt/expand/") || dataDir.startsWith("/mnt/expand/") -> "adopted storage ($dataDir)"
        !onDefaultVolume -> "a storage volume other than internal"
        else -> null
    }
}
