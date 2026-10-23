package com.jrees.wowcompanion

import org.json.JSONObject

/**
 * WHERE THE ADD-ON'S DOCUMENTS COME FROM (Streaming, S2; STREAMING_PLAN.md section 2). Everything above this reads the same
 * documents either way:
 *  - LocalLog = [LogTail]: the game runs in the Thor's container and its General.log is tailed here (today's path);
 *  - BridgeLog = Streaming's WebSocket client (the fork's stream package): the game runs on the PC and DS Forever Desktop's bridge
 *    sends the same WCB1 lines from the PC's General.log (docs/STREAM_BRIDGE_PROTOCOL.md); the same [Wcb1Assembler] joins them.
 */
interface LogSource {
    /** "local" or "bridge" (the log line and the tests) */
    val kind: String
    fun start()
    fun stop()
    /** sequence gaps seen (lines the add-on refused) */
    fun gapCount(): Int
}

/** which game the app plays: LOCAL = the container on the Thor (today), STREAM = the PC through Moonlight + the Desktop bridge */
enum class PlayMode { LOCAL, STREAM }

/**
 * THE ONE SWITCH between the two (set by Streaming's entry before the companion starts; Local by default, so nothing changes for
 * Local). [bridgeLog] is Streaming's factory for its BridgeLog, set while a paired PC is known.
 */
object PlayModes {
    @Volatile var current: PlayMode = PlayMode.LOCAL

    /** Streaming's BridgeLog factory (null = no paired PC: the companion keeps the local log) */
    @Volatile var bridgeLog: ((onMessage: (JSONObject) -> Unit, onStatus: (String) -> Unit) -> LogSource)? = null

    /** a key that changes when the bridge's PC does (CompanionHost restarts its source on a change) */
    @Volatile var bridgeKey: String = ""

    /** the companion's "follow the new source" (CompanionHost sets it while it runs) */
    @Volatile var onChanged: (() -> Unit)? = null

    /** Streaming's entry: the mode and the bridge's PC in one step, then the companion follows them */
    fun set(mode: PlayMode, bridge: ((onMessage: (JSONObject) -> Unit, onStatus: (String) -> Unit) -> LogSource)?, key: String) {
        val changed = mode != current || key != bridgeKey || (bridge == null) != (bridgeLog == null)
        current = mode; bridgeLog = bridge; bridgeKey = key
        if (changed) onChanged?.invoke()
    }

    /**
     * The source for [mode]: Streaming with a bridge factory -> the bridge, anything else -> [local] (pure: the tests drive it).
     * Streaming WITHOUT a factory keeps the local log rather than nothing (a Streaming entry that never got a PC is a bug to see, not
     * a silent blank companion): [onStatus] says so.
     */
    fun pick(
        mode: PlayMode,
        bridge: ((onMessage: (JSONObject) -> Unit, onStatus: (String) -> Unit) -> LogSource)?,
        onMessage: (JSONObject) -> Unit,
        onStatus: (String) -> Unit,
        local: () -> LogSource,
    ): LogSource = when {
        mode == PlayMode.STREAM && bridge != null -> bridge(onMessage, onStatus)
        mode == PlayMode.STREAM -> { onStatus("streaming: no paired PC, reading the local log"); local() }
        else -> local()
    }
}
