package com.winlator.xserver.extensions;

import android.os.SystemClock;

import com.jrees.wowcompanion.LaunchLoad;
import com.jrees.wowcompanion.LaunchPicture;
import com.winlator.renderer.GPUImage;
import com.winlator.renderer.Texture;
import com.winlator.xserver.Drawable;

import java.nio.ByteBuffer;

/**
 * DS FOREVER, THE LAUNCH'S HAND-OVER (2026-11-24, LaunchLoad): a frame the game presents (Present's pixmap, Vortek's window update),
 * looked at only while DS Forever's launch screen waits for the game's first real picture ({@link LaunchLoad#getWatching()}: one
 * volatile read a frame otherwise). The look is {@link LaunchPicture#lit}: about 300 reads of the frame's own CPU mapping (the
 * GPUImage's locked hardware buffer, or the drawable's buffer), no copy, no GPU read-back. Called on the X server's request thread.
 */
public final class LaunchFrameWatch {
    private static int phase;

    private LaunchFrameWatch() {}

    public static void offer(Drawable d) {
        if (d == null || !LaunchLoad.INSTANCE.getWatching()) return;
        boolean picture;
        try {
            synchronized (d.renderLock) {
                ByteBuffer buf = null;
                int stride = d.width;
                Texture tex = d.getTexture();
                if (tex instanceof GPUImage) {
                    // the frame is in the hardware buffer: its CPU mapping or nothing (the drawable's own bytes are not the frame)
                    GPUImage g = (GPUImage) tex;
                    buf = g.getVirtualData();
                    if (g.getStride() > 0) stride = g.getStride();
                } else if (!(tex instanceof com.winlator.renderer.AHBImage)) {     // (SurfaceFlinger's buffers: not ours to map)
                    buf = d.getBuffer();
                    if (buf != null && d.height > 0) stride = buf.capacity() / (d.height * 4);
                }
                if (buf == null) {
                    LaunchLoad.INSTANCE.unreadable(SystemClock.uptimeMillis());   // drawn, but not ours to look at: a short fallback
                    return;
                }
                picture = LaunchPicture.INSTANCE.lit(buf, d.width, d.height, stride, phase++);
            }
        } catch (Throwable t) {
            return;                       // never the X server's problem: the timeout hands over instead
        }
        LaunchLoad.INSTANCE.frame(picture, SystemClock.uptimeMillis());
    }
}
