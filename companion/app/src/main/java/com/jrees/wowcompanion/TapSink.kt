package com.jrees.wowcompanion

/**
 * WHERE THE COMPANION'S TAPS AND KEYS GO (Streaming, S2; STREAMING_PLAN.md section 2). Every tap and key chord of the companion
 * already goes through ONE place, [InputSink] (TapBridge -> RootShell -> InputSink.play -> [InputSink.sink]); a TapSink is what
 * stands behind it:
 *  - ContainerKeys = the launcher's X server (today's path: the container's game);
 *  - StreamKeys = Moonlight's keyboard + mouse channel to WoW on the PC (the fork's stream package).
 * The tap pad, its rows and the chord rules are untouched: InputSink.play still refuses Alt, F-keys and system keys in a key-channel
 * line before any sink sees it, and StreamKeys refuses them a second time (a real Windows desktop is behind it: Alt+F4, Win, Ctrl+Esc).
 */
interface TapSink : InputSink.Sink {
    /** "container" or "stream" (the log line and the tests) */
    val route: String
}

/** today's path: the launcher's X server, unchanged (every call passes straight through) */
class ContainerKeys(private val x: InputSink.Sink) : TapSink, InputSink.Sink by x {
    override val route get() = "container"
}

object TapSinks {
    /**
     * The sink for [mode]: LOCAL -> the container's X server (wrapped as [ContainerKeys]), STREAM -> [stream]. Null = no input yet
     * (the game screen or the stream is not up): nothing is sent, exactly as before a game screen exists. A Streaming session NEVER
     * falls back to the container (keys meant for the PC must not land in a local game, nor the other way round).
     */
    fun select(mode: PlayMode, container: InputSink.Sink?, stream: TapSink?): TapSink? = when (mode) {
        PlayMode.LOCAL -> container?.let { it as? ContainerKeys ?: ContainerKeys(it) }
        PlayMode.STREAM -> stream
    }

    /** sets [InputSink.sink] for [mode] and says which route it took ("none" = no input) */
    fun wire(mode: PlayMode, container: InputSink.Sink?, stream: TapSink?): String {
        val s = select(mode, container, stream)
        InputSink.sink = s
        return s?.route ?: "none"
    }
}
