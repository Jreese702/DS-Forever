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

    /** The JNI upload, as a seam for tests: nativeUpdateCursorImage(handle, px, w, h, hotX, hotY). */
    public interface Upload { void send(long handle, ByteBuffer px, short w, short h, short hotX, short hotY); }

    /** Creates the native renderer context and returns its handle (0 on failure). */
    public interface Init { long create(); }

    /**
     * Review follow-up (first cursor creation still raced the render thread): creates the native context,
     * then, BEFORE the handle is returned (so before any other thread can reach it), uploads one fully
     * transparent SIZE x SIZE cursor so native ensureCursorTex makes the cursor image + view while no frame
     * can be recording. Why that is safe, from VulkanRendererContext.cpp: renderLoop (:1066) sleeps on dirtyCV
     * until needsRender / fbResized / cursorMoved is set; all three start false (VulkanRendererContext.h) and
     * the constructor (:15-26) never sets them; only JNI calls do, and nobody else has the handle yet. So the
     * first frame can only start after this upload's own notify (:1606), with the texture already built under
     * renderMutex. Every later upload is the same size, so ensureCursorTex (:758) never recreates it.
     */
    public static long initPrimed(Init init, CursorCanvas canvas, Upload upload) {
        long h = init.create();
        if (h != 0) upload.send(h, canvas.blank(), (short) SIZE, (short) SIZE, (short) 0, (short) 0);
        return h;
    }

    /** Clears the whole canvas to transparent and returns it (position 0). */
    public synchronized ByteBuffer blank() {
        byte[] zero = new byte[SIZE * 4];
        canvas.clear();
        for (int y = 0; y < SIZE; y++) canvas.put(zero);
        canvas.clear();
        return canvas;
    }

    /** A hotspot inside the canvas: a cursor larger than SIZE is clipped, so its hotspot is kept on the canvas. */
    public static short clampHot(int v) { return (short) Math.max(0, Math.min(v, SIZE - 1)); }

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
