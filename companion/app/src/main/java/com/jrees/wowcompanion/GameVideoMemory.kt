package com.jrees.wowcompanion

/**
 * THE GAME'S VIDEO MEMORY AND TEXTURE FORMAT IN OUR OWN CONTAINER (#56 G1 + G3, the user's approval 2026-11-19). Our emulator's
 * config, never a game CVar: the game's settings are not touched. Local play only (WoWForeverScreen's container; streaming runs on
 * the PC and never builds it).
 *
 * G1, THE CAP. The Thor's GPU shares the RAM: Turnip reports ONE device-local heap of about 3/4 of it (~5.4 GB on an 8 GB Thor,
 * ~8.4 GB on a 12 GB, ~11.4 GB on a 16 GB) and DXVK hands that to the game as DXGI DedicatedVideoMemory (SharedSystemMemory the
 * same: a single heap counts for both). The game sizes its texture budget against it. At a low-memory kill (the #56 table, 136 kills)
 * the game's GPU memory (kgsl) was: 8 GB median 1268 / p90 2463 / max 2786 MB; 12 GB median 2388 / p90 2902 / max 3503; 16 GB median
 * 2833 / p90 3513 / max 3976. The caps sit above each model's typical use and below its tail: 8 GB 2048, 12 GB 3072, 16 GB 4096 (only
 * a guard there: above every 16 GB kill seen). An unknown RAM gets no cap.
 *
 * HOW IT GETS THERE: DXVK 2.4.1 reads DXVK_CONFIG ("key = value" entries split by ';'). The container's own environment is applied
 * after DXVKHelper's, so this value wins over DXVKHelper's (which writes quotes and newlines and nothing a DXVK option can parse:
 * the GameNative dxwrapperConfig "maxDeviceMemory" route never worked). Only the DXGI report changes: DXVK never refuses memory
 * past it, and QueryVideoMemoryInfo's budget still comes from the driver (VK_EXT_memory_budget). NOT the Wrapper's
 * WRAPPER_VMEM_MAX_SIZE (graphicsDriverConfig "maxDeviceMemory"): its behaviour past the heap size is not known, and a refused
 * allocation would crash the game where the DXGI report cannot.
 *
 * G3, BCn. Adreno's hardware samples BC1-BC7 and Turnip exposes textureCompressionBC, so the Wrapper's BCn emulation is off on an
 * Adreno ("none" = WRAPPER_EMULATE_BCN 0: the textures stay compressed, as the game ships them). Any other GPU keeps "auto" (the
 * Wrapper's own decision: emulate only what the driver cannot sample).
 */
object GameVideoMemory {
    /** the cap in MB for a device that Android counts [totalMb] of RAM on ([lite]: the Thor Lite, an 8 GB model); null = no cap */
    fun capMb(totalMb: Long, lite: Boolean = false): Int? = when {
        totalMb <= 0L -> null
        lite || totalMb < DeviceTier.EIGHT_GB_MB -> CAP_8GB
        totalMb < DeviceTier.LOW_MB -> CAP_12GB
        else -> CAP_16GB
    }
    const val CAP_8GB = 2048
    const val CAP_12GB = 3072
    const val CAP_16GB = 4096

    const val DXVK_CONFIG = "DXVK_CONFIG"

    /** DXVK_CONFIG's value for [capMb]: both DXGI sizes (no spaces: the container's env string splits on them) */
    fun dxvkConfig(capMb: Int): String = "dxgi.maxDeviceMemory=$capMb;dxgi.maxSharedMemory=$capMb"

    /** the container's extra environment ("" = none): [capMb] null or the setting off = nothing (the driver's own size) */
    fun env(capMb: Int?, on: Boolean): String = if (on && capMb != null && capMb > 0) "$DXVK_CONFIG=${dxvkConfig(capMb)}" else ""

    /**
     * LIGHT 8'S TWO DXVK OPTIONS (LIGHT8_PLAN.md change 5; the user's answer Q2 2026-11-21: both, the 8 GB Thors only, logged once per
     * launch): DXVK's pipeline-lifetime tracking (pipelines not used lately are let go) and 4 shader compiler threads (fewer shaders
     * compiled at once while the game loads). DXVK 2.4.1's own option names, no spaces (the container's env string splits on them)
     */
    const val LIGHT8_DXVK = "dxvk.trackPipelineLifetime=true;dxvk.numCompilerThreads=4"

    /** the container's extra environment with Light 8's options ([light8]); off Light 8 exactly [env] (test build 1516's). Built apart
     *  from the cap: the options are there even when the cap is off (the setting, an unknown RAM) */
    fun env(capMb: Int?, on: Boolean, light8: Boolean): String {
        if (!light8) return env(capMb, on)
        val cap = if (on && capMb != null && capMb > 0) dxvkConfig(capMb) + ";" else ""
        return "$DXVK_CONFIG=$cap$LIGHT8_DXVK"
    }

    /** the launch's Light 8 line (mem.log, once per launch): "light8 on, dxcfg dxgi.maxDeviceMemory=2048;...;dxvk.numCompilerThreads=4" */
    fun dxcfgLine(env: String): String = "light8 on, dxcfg " + env.removePrefix("$DXVK_CONFIG=").ifEmpty { "none" }

    /** the Wrapper's BCn emulation for the GPU: off on an Adreno (native BCn), the Wrapper's own "auto" anywhere else */
    fun bcnEmulation(adreno: Boolean): String = if (adreno) "none" else "auto"

    /** the launch's line (logcat + mem.log): "video memory cap 3072 MB (12 GB), BCn native (emulation none)" */
    fun line(capMb: Int?, on: Boolean, totalMb: Long, bcn: String): String {
        val cap = when {
            !on -> "video memory cap off (setting)"
            capMb == null -> "video memory cap none (RAM unknown)"
            else -> "video memory cap $capMb MB (${totalMb} MB RAM)"
        }
        return "$cap, BCn ${if (bcn == "none") "native (emulation none)" else "emulation $bcn"}"
    }

    /** the short part of every WCMEM line while this launch's game runs: "vcap 3072" / "vcap off" */
    fun short(capMb: Int?, on: Boolean): String = if (on && capMb != null) "vcap $capMb" else "vcap off"
}
