package com.winlator.renderer;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * DS Forever (crash 2026-11-12 21:06, SIGSEGV at 0x80 in vkCmdCopyBufferToImage from libvulkan_renderer.so):
 * the native renderer's updateCursorImage destroys and recreates the cursor VkImage + staging buffer
 * whenever the cursor SIZE changes, with no fence wait, while the render thread may be recording a copy
 * into the old image (use-after-destroy race). The prebuilt .so cannot be rebuilt yet, so the Java side
 * never sends a different size: every cursor is copied onto one fixed SIZE x SIZE transparent canvas
 * (image at the top-left, so the hotspot is unchanged; anything larger is clipped). After the first upload
 * the native texture is never recreated; a racing shape change can only tear one frame of the cursor.
 *
 * The cursor quad is drawn with alpha blending (useTexAlpha=1), so the transparent padding is invisible.
 */
public final class CursorCanvas {
    /** Fixed canvas edge. X cursors are 16..64 px (the root arrow is 16, the game's are 32). */
    public static final int SIZE = 128;

    private final ByteBuffer canvas;

    public CursorCanvas() {
        canvas = ByteBuffer.allocateDirect(SIZE * SIZE * 4).order(ByteOrder.nativeOrder());
    }

    public ByteBuffer buffer() { return canvas; }

    /**
     * Source row stride in pixels: the same estimate the scanout path uses (capacity / (h*4)),
     * never less than the width.
     */
    public static int strideOf(ByteBuffer src, int w, int h) {
        if (src == null || w <= 0 || h <= 0) return w;
        int s = src.capacity() / (h * 4);
        return Math.max(s, w);
    }

    /**
     * Copies the w x h BGRA image (row stride {@code stridePx} pixels) to the top-left of the canvas,
     * clears the rest to transparent, and clips anything beyond SIZE. Returns the canvas buffer
     * (position 0), or null when the source is unusable.
     */
    public synchronized ByteBuffer fill(ByteBuffer src, int w, int h, int stridePx) {
        if (src == null || w <= 0 || h <= 0) return null;
        if (stridePx < w) stridePx = w;
        int cw = Math.min(w, SIZE), ch = Math.min(h, SIZE);
        // The last row only needs w pixels, so a tightly packed buffer is enough.
        long need = ((long) (h - 1) * stridePx + w) * 4L;
        if (src.capacity() < need) return null;
        byte[] zero = new byte[SIZE * 4];
        canvas.clear();
        for (int y = 0; y < SIZE; y++) canvas.put(zero);
        ByteBuffer s = src.duplicate();
        byte[] row = new byte[cw * 4];
        for (int y = 0; y < ch; y++) {
            s.clear();
            s.position(y * stridePx * 4);
            s.get(row, 0, cw * 4);
            canvas.position(y * SIZE * 4);
            canvas.put(row, 0, cw * 4);
        }
        canvas.clear();
        return canvas;
    }
}
