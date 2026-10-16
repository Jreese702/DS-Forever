package com.winlator.renderer;

import com.winlator.widget.FrameRating;
import com.winlator.widget.XServerRendererView;

/**
 * Shared surface used by the X server and call sites that don't care whether
 * the compositor is the Vulkan-backed {@link VulkanRenderer} or the legacy
 * OpenGL {@link GLRenderer} (only kept alive for the VirGL passthrough path).
 */
public interface XServerRenderer {
    boolean isFullscreen();
    void setCursorVisible(boolean visible);
    String getForceFullscreenWMClass();
    void setForceFullscreenWMClass(String wmClass);
    void setUnviewableWMClasses(String... classes);
    void setOnFrameRenderedListener(Runnable r);
    void setFrameRating(FrameRating fr);
    XServerRendererView getRendererView();
    /** DS Forever (f-trackpad-keyboard, the user on 1.0.8: "The track pad doesn't actually show the mouse cursor on the top screen"):
     * the game hides its cursor in gamepad mode (an empty-mask X cursor), so while the companion's trackpad is open the renderer
     * draws the launcher's own arrow (R.drawable.cursor, the root cursor) at the X pointer instead. Nothing is sent to the game. */
    default void setForceCursor(boolean force) {}
}
