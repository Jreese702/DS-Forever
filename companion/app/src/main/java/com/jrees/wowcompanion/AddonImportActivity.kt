package com.jrees.wowcompanion

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns

/**
 * Settings > Add-ons > Import (ImportedAddons; the user, 2026-11-16: "maybe we just do an import addons button then?"): the bottom
 * screen is a Presentation, which can't take a picker's result, so this invisible activity (started by the host's openOnBottom on the
 * bottom screen's display, as FrameGenPickActivity) opens Android's own file picker for one or more .zip files and hands them to
 * ImportedAddons.receive (copied into private storage off the main thread), then closes.
 * Read access to the picked files only: no storage permission is asked for. (A folder pick, ACTION_OPEN_DOCUMENT_TREE, is left out:
 * add-ons come as zips, and a tree walk through DocumentsContract would be a second, heavier path for the same rules.)
 */
class AddonImportActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return             // the picker is already open (a recreate)
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip",
                "application/octet-stream"))
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        runCatching { startActivityForResult(pick, REQ) }.onFailure { finish() }
    }

    @Deprecated("Activity result on a plain Activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            val uris = ArrayList<Uri>()
            data.clipData?.let { c -> for (i in 0 until c.itemCount) c.getItemAt(i)?.uri?.let { uris.add(it) } }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
            val cr = applicationContext.contentResolver
            ImportedAddons.receive(uris.map { u ->
                val name = runCatching {
                    cr.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
                }.getOrNull() ?: u.lastPathSegment ?: "zip"
                ImportedAddons.Picked(name) { cr.openInputStream(u) }
            })
        }
        finish()
    }

    /** however it ends (a pick, Back, a failure): the bottom screen's window comes back */
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) ImportedAddons.onPickerClosed?.invoke()
    }

    private companion object { const val REQ = 7102 }
}
