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
        if (selectedRenderer.equalsIgnoreCase("vulkan")) {
            Drawable.DRAWABLE_ASR_MODE(false);
            renderer = new VulkanRenderer(this, xServer);
        } else if (selectedRenderer.equalsIgnoreCase("surfaceflinger")) {
            Drawable.DRAWABLE_ASR_MODE(true);
            renderer = new ASurfaceRenderer(this, xServer);
        }
    }

    /** null = not decided yet; else whether the surface is now fixed at the X screen's size */
    private Boolean surfaceFixed = null;

    /**
     * PR 615 (based on a pull request by RenaeTheRaven): [fixed] = the surface's buffers are the X screen's size (e.g. the game's
     * 1280x720 on a 1080p panel), so the renderer's composite, frame generation and present work at the game's size and the display
     * hardware scales to the panel. Without it the surface follows the view: everything after the game ran at the panel's 1920x1080
     * (2.25x the pixels; the frame generation flow pass too). The view itself stays full size, so touch mapping (which uses the view's
     * size) is unchanged. Not fixed = the view's size, as before: the quick menu's upscaling (FSR, DLS) and scaling modes need it
     * (app.gamenative.ui.util.SurfaceFit; the user's ruling 2026-11-27). Called before the surface exists (the container's saved pick)
     * and at every apply of the quick menu's screen effects; only a change touches the holder.
     */
    public void fitSurfaceToScreen(boolean fixed) {
        int w = xServer.screenInfo.width, h = xServer.screenInfo.height;
        boolean want = fixed && w > 0 && h > 0;
        if (surfaceFixed != null && surfaceFixed == want) return;
        surfaceFixed = want;
        if (want) {
            getHolder().setFixedSize(w, h);
            timber.log.Timber.i("surface fixed at the X screen's %dx%d", w, h);
        } else {
            getHolder().setSizeFromLayout();
            timber.log.Timber.i("surface follows the view (a quick-menu scaling mode)");
        }
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
