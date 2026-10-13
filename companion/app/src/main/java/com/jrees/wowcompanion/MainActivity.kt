package com.jrees.wowcompanion

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View

/**
 * THE APP'S ACTIVITY: a thin shell around [CompanionHost] (the rooms refactor, S1, 2026-10-08). Everything it used to hold lives in
 * CompanionHost.kt, unchanged; edits go there (AGENT_LOG first). The private launcher builds the same host on its bottom screen.
 */
class MainActivity : Activity(), HostShell {
    private lateinit var host: CompanionHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        host = CompanionHost(this, this)
        host.onCreate(savedInstanceState)
    }

    override fun onStart() { super.onStart(); if (::host.isInitialized) host.onStart() }
    override fun onStop() { if (::host.isInitialized) host.onStop(); super.onStop() }
    override fun onDestroy() { if (::host.isInitialized) host.onDestroy(); super.onDestroy() }

    override fun setHostContent(v: View) = setContentView(v)
    override fun hostGone(): Boolean = isFinishing || isDestroyed
    override fun recreateHost() = recreate()
    override fun hostWindow() = window
    override fun hostIntent(): Intent? = intent
    override fun startHostActivity(i: Intent) = startActivity(i)
}
