package com.winlator.renderer;

import java.util.Objects;

/**
 * Issue 153: what the native renderer was last given for frame generation, so a settings push that changes nothing never
 * reaches it. Every push of the container's settings sends all four values again, and a shader push makes the renderer wait
 * for the GPU and rebuild its shaders even when the cache is the same; meanwhile window updates wait on the renderer's lock.
 * {@link #forget()} when a new native renderer is created or the old one is destroyed: it starts from its own defaults.
 * Not thread-safe: the renderer calls it holding its frame-generation lock.
 */
final class FrameGenPushes {
    private boolean shadersSent, modeSent, refreshSent, enabledSent;
    private String shaders;
    private int multiplier, targetRate, flowScalePct;
    private float refreshHz;
    private boolean enabled;

    void forget() {
        shadersSent = modeSent = refreshSent = enabledSent = false;
        shaders = null;
    }

    /** True when the shader cache path must be sent (it differs from the last one sent). */
    boolean shaders(String cachePath) {
        if (shadersSent && Objects.equals(cachePath, shaders)) return false;
        shadersSent = true;
        shaders = cachePath;
        return true;
    }

    boolean mode(int multiplier, int targetRate, int flowScalePct) {
        if (modeSent && multiplier == this.multiplier && targetRate == this.targetRate && flowScalePct == this.flowScalePct) return false;
        modeSent = true;
        this.multiplier = multiplier;
        this.targetRate = targetRate;
        this.flowScalePct = flowScalePct;
        return true;
    }

    boolean refreshRate(float hz) {
        if (refreshSent && Float.compare(hz, refreshHz) == 0) return false;
        refreshSent = true;
        refreshHz = hz;
        return true;
    }

    boolean enabled(boolean enabled) {
        if (enabledSent && enabled == this.enabled) return false;
        enabledSent = true;
        this.enabled = enabled;
        return true;
    }
}
