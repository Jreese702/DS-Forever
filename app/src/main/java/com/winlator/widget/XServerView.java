package com.winlator.widget;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.annotation.NonNull;

import com.winlator.renderer.ASurfaceRenderer;
import com.winlator.renderer.VulkanRenderer;
import com.winlator.renderer.XServerRenderer;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XServer;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressLint("ViewConstructor")
public class XServerView extends SurfaceView implements SurfaceHolder.Callback, XServerRendererView {
    private XServerRenderer renderer;
    private final XServer xServer;
    private final ExecutorService eventExecutor = Executors.newSingleThreadExecutor();
    private int frameRateLimit = 0;

    public XServerView(Context context, XServer xServer, String selectedRenderer) {
        super(context);
        setLayoutParams(new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        getHolder().addCallback(this);
        this.xServer = xServer;
        fixSurfaceToScreen(xServer);
        if (selectedRenderer.equalsIgnoreCase("vulkan")) {
            Drawable.DRAWABLE_ASR_MODE(false);
            renderer = new VulkanRenderer(this, xServer);
        } else if (selectedRenderer.equalsIgnoreCase("surfaceflinger")) {
            Drawable.DRAWABLE_ASR_MODE(true);
            renderer = new ASurfaceRenderer(this, xServer);
        }
    }

    /**
     * custom-thor: the surface's buffers are the X screen's size (the game's 1280x720 on the Thor's 1080p top screen), so the renderer's
     * composite, frame generation and present work at 720p and the display hardware scales to the panel for free. The view itself stays
     * full size, so touch mapping (which uses the view's size) is unchanged. The game folder's ".ds-fullsurface" switch file turns it off.
     */
    private void fixSurfaceToScreen(XServer xServer) {
        int w = xServer.screenInfo.width, h = xServer.screenInfo.height;
        if (w <= 0 || h <= 0) return;
        if (new java.io.File("/storage/emulated/0/WoW Forever", ".ds-fullsurface").isFile()) {
            timber.log.Timber.i("custom-thor: surface follows the view (.ds-fullsurface)");
            return;
        }
        getHolder().setFixedSize(w, h);
        timber.log.Timber.i("custom-thor: surface fixed at the X screen's %dx%d", w, h);
    }

    public XServer getxServer() {
        return xServer;
    }

    public XServerRenderer getRenderer() {
        return renderer;
    }

    @Override
    public void surfaceCreated(@NonNull SurfaceHolder holder) {
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceCreated(holder.getSurface());
        }
        if (renderer instanceof ASurfaceRenderer scRenderer) {
            scRenderer.onSurfaceCreated(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceChanged(width, height);
        }
        if (renderer instanceof ASurfaceRenderer aRenderer) {
            aRenderer.onSurfaceChanged(width, height);
        }
    }

    @Override
    public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.onSurfaceDestroyed();
        }
        if (renderer instanceof ASurfaceRenderer aRenderer) {
            aRenderer.onSurfaceDestroyed();
        }
    }

    public void queueEvent(Runnable r) {
        eventExecutor.execute(r);
    }

    public void onPause() {}
    public void onResume() {}

    public int getFrameRateLimit() {
        return frameRateLimit;
    }

    public void setFrameRateLimit(int frameRateLimit) {
        this.frameRateLimit = Math.max(0, frameRateLimit);
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.setFpsLimit(this.frameRateLimit);
        }
    }

    public void requestRender() {
        if (renderer instanceof VulkanRenderer vkRenderer) {
            vkRenderer.queueSceneUpdate();
        }
        if (renderer instanceof ASurfaceRenderer aRenderer) {
            aRenderer.updateScene();
        }
    }
}
