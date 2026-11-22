package com.winlator.xserver;

import android.graphics.Rect;
import android.util.Log;
import android.util.SparseArray;

import com.winlator.math.Mathf;
import com.winlator.renderer.XServerRenderer;
import com.winlator.winhandler.MouseEventFlags;
import com.winlator.winhandler.WinHandler;
import com.winlator.xserver.extensions.BigReqExtension;
import com.winlator.xserver.extensions.DRI3Extension;
import com.winlator.xserver.extensions.Extension;
import com.winlator.xserver.extensions.GenericEventExtension;
import com.winlator.xserver.extensions.MITSHMExtension;
import com.winlator.xserver.extensions.PresentExtension;
import com.winlator.xserver.extensions.SyncExtension;
import com.winlator.xserver.extensions.XInput2Extension;

import java.nio.charset.Charset;
import java.util.EnumMap;
import java.util.concurrent.locks.ReentrantLock;

public class XServer {
    public enum Lockable {WINDOW_MANAGER, PIXMAP_MANAGER, DRAWABLE_MANAGER, GRAPHIC_CONTEXT_MANAGER, INPUT_DEVICE, CURSOR_MANAGER, SHMSEGMENT_MANAGER}
    public static final short VERSION = 11;
    public static final String VENDOR_NAME = "Elbrus Technologies, LLC";
    public static final Charset LATIN1_CHARSET = Charset.forName("latin1");
    public final SparseArray<Extension> extensions = new SparseArray<>();
    public final ScreenInfo screenInfo;
    public final PixmapManager pixmapManager;
    public final ResourceIDs resourceIDs = new ResourceIDs(128);
    public final GraphicsContextManager graphicsContextManager = new GraphicsContextManager();
    public final SelectionManager selectionManager;
    public final DrawableManager drawableManager;
    public final WindowManager windowManager;
    public final CursorManager cursorManager;
    public final Keyboard keyboard = Keyboard.createKeyboard(this);
    public final Pointer pointer = new Pointer(this);
    public final InputDeviceManager inputDeviceManager;
    public final GrabManager grabManager;
    private boolean isGrabbed = false;
    private XClient grabbingClient = null;
    private SHMSegmentManager shmSegmentManager;
    private XServerRenderer renderer;
    private WinHandler winHandler;
    private final EnumMap<Lockable, ReentrantLock> locks = new EnumMap<>(Lockable.class);
    private volatile boolean relativeMouseMovement = false;
    private final boolean mouseDragCompatibilityEnabled;
    private boolean simulateTouchScreen = false;
    private final boolean runningFromGlibc;
    private volatile boolean flatPresentationEnabled = true;

    public XServer(ScreenInfo screenInfo, boolean useGlibcContainer) {
        this(screenInfo, useGlibcContainer, false);
    }

    public XServer(ScreenInfo screenInfo, boolean useGlibcContainer, boolean mouseDragCompatibilityEnabled) {
        Log.d("XServer", "Creating xServer " + screenInfo);
        this.screenInfo = screenInfo;
        for (Lockable lockable : Lockable.values()) locks.put(lockable, new OwnedLock());
        inputQueue.setSlowListener(this::reportSlowInput);

        pixmapManager = new PixmapManager();
        drawableManager = new DrawableManager(this);
        cursorManager = new CursorManager(drawableManager);
        windowManager = new WindowManager(screenInfo, drawableManager);
        selectionManager = new SelectionManager(windowManager);
        inputDeviceManager = new InputDeviceManager(this);
        grabManager = new GrabManager(this);
        runningFromGlibc = useGlibcContainer;
        this.mouseDragCompatibilityEnabled = shouldAdvertiseGenericEvents(
                useGlibcContainer,
                mouseDragCompatibilityEnabled
        );

        DesktopHelper.attachTo(this);
        setupExtensions();
    }

    public boolean isRelativeMouseMovement() {
        return relativeMouseMovement;
    }

    public void setRelativeMouseMovement(boolean relativeMouseMovement) {
        this.relativeMouseMovement = relativeMouseMovement;
    }

    public boolean isMouseDragCompatibilityEnabled() {
        return mouseDragCompatibilityEnabled;
    }

    public boolean isSimulateTouchScreen() { return simulateTouchScreen; }

    public void setSimulateTouchScreen(boolean simulateTouchScreen) {
        this.simulateTouchScreen = simulateTouchScreen;
    }

    public XServerRenderer getRenderer() {
        return renderer;
    }

    public void setRenderer(XServerRenderer renderer) {
        this.renderer = renderer;
    }

    public void setRenderingEnabled(boolean enabled) {
        // intentionally empty
    }

    public void setFlatPresentationEnabled(boolean enabled) {
        flatPresentationEnabled = enabled;
    }

    public boolean isFlatPresentationEnabled() {
        return flatPresentationEnabled;
    }

    public WinHandler getWinHandler() {
        return winHandler;
    }

    public void setWinHandler(WinHandler winHandler) {
        this.winHandler = winHandler;
    }

    public SHMSegmentManager getSHMSegmentManager() {
        return shmSegmentManager;
    }

    public void setSHMSegmentManager(SHMSegmentManager shmSegmentManager) {
        this.shmSegmentManager = shmSegmentManager;
    }

    private class SingleXLock implements XLock {
        private final ReentrantLock lock;

        private SingleXLock(Lockable lockable) {
            this.lock = locks.get(lockable);
            lock.lock();
        }

        @Override
        public void close() {
            lock.unlock();
        }
    }

    private class MultiXLock implements XLock {
        private final Lockable[] lockables;

        private MultiXLock(Lockable[] lockables) {
            this.lockables = lockables;
            for (Lockable lockable : lockables) locks.get(lockable).lock();
        }

        @Override
        public void close() {
            for (int i = lockables.length - 1; i >= 0; i--) {
                locks.get(lockables[i]).unlock();
            }
        }
    }

    public XLock lock(Lockable lockable) {
        return new SingleXLock(lockable);
    }

    public XLock lock(Lockable... lockables) {
        return new MultiXLock(lockables);
    }

    public XLock lockAll() {
        return new MultiXLock(Lockable.values());
    }

    public Extension getExtensionByName(String name) {
        for (int i = 0; i < extensions.size(); i++) {
            Extension extension = extensions.valueAt(i);
            if (extension.getName().equals(name)) return extension;
        }
        return null;
    }

    // ---------------------------------------------------------------- #153: every input through one queue, never the caller's wait
    /**
     * THE INPUT QUEUE (#153): every inject* below queues its work on [inputQueue]'s own thread, so the caller (the UI thread for a
     * touch, a pad button, a key) never waits on WINDOW_MANAGER / INPUT_DEVICE while an X request thread or the renderer holds them.
     * The order is kept (one FIFO worker). What a caller reads back right after sending - is a button down, is Shift held - is the
     * LOGICAL state: what the X server will hold once everything sent so far has run ([isPointerButtonPressed], [isKeyPressed],
     * [isShiftDown]), set in the same step as the queueing, so a release that checks "pressed?" is never lost behind a slow lock.
     * Work that must read the X server's own state and act on it goes through [postInput] (it runs on the input thread, in order).
     */
    private final XInputQueue inputQueue = new XInputQueue("XInput", new XInputQueue.Mover() {
        @Override public void moveTo(int x, int y) { pointerMoveNow(x, y); }
        @Override public void moveBy(int dx, int dy) { pointerMoveDeltaNow(dx, dy); }
    });
    private final Object logical = new Object();
    private int logicalButtons = 0;                          // Pointer.Button.flag() bits
    private final boolean[] logicalKeys = new boolean[256];  // by X keycode
    // the hold a sender meant (#153 review): when each press was SENT (under [logical]) and when it RAN (input thread only)
    private final long[] keySentNs = new long[256], keyRanNs = new long[256];
    private final long[] buttonSentNs = new long[8], buttonRanNs = new long[8];
    /** the most of a sender's press-to-release gap kept when the queue ran behind (a long hold is never replayed in full) */
    public static final long MAX_KEPT_HOLD_MS = 60L;

    public XInputQueue getInputQueue() { return inputQueue; }

    /** [op] on the input thread after everything sent before it (at once when already there): input that reads the X state first */
    public void postInput(Runnable op) {
        inputQueue.post(op);
    }

    /** the button is down once everything sent so far has run (the caller's view; any thread) */
    public boolean isPointerButtonPressed(Pointer.Button button) {
        synchronized (logical) { return (logicalButtons & button.flag()) != 0; }
    }

    /** the key is down once everything sent so far has run (any thread) */
    public boolean isKeyPressed(XKeycode key) {
        synchronized (logical) { return logicalKeys[key.getId() & 0xff]; }
    }

    /** a Shift is down once everything sent so far has run (the keyboard's modifier flag 1; any thread) */
    public boolean isShiftDown() {
        return isKeyPressed(XKeycode.KEY_SHIFT_L) || isKeyPressed(XKeycode.KEY_SHIFT_R);
    }

    /** [op] after [state] changed the logical view, in one step with its queueing; [op] at once (outside the step) on the input thread */
    private void submit(Runnable state, Runnable op) {
        boolean here;
        synchronized (logical) {
            state.run();
            here = inputQueue.isInputThread();
            if (!here) inputQueue.post(op);
        }
        if (here) op.run();
    }

    public void injectPointerMove(int x, int y) {
        inputQueue.moveTo(x, y);
    }

    public void injectPointerMoveDelta(int dx, int dy) {
        if (dx == 0 && dy == 0) return;
        // relative mouse: straight to the Windows side as before #153 (no X lock involved, nothing waits behind a stalled key)
        WinHandler handler = winHandler;
        if (relativeMouseMovement && handler != null) {
            handler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
            return;
        }
        inputQueue.moveBy(dx, dy);
    }

    /** the pointer by [dx], [dy] from where it IS when this runs (absolute, no XInput raw motion): the touchpad's cursor drag */
    public void injectPointerMoveBy(int dx, int dy) {
        postInput(() -> pointerMoveNow(pointer.getX() + dx, pointer.getY() + dy));
    }

    public void injectPointerButtonPress(Pointer.Button buttonCode) {
        submitButtonPress(buttonCode, null);
    }

    public void injectPointerButtonRelease(Pointer.Button buttonCode) {
        submitButtonRelease(buttonCode, null);
    }

    /**
     * ONE click at [x], [y] held [holdMs], then the pointer goes back where it was (DS Forever's cell taps). The spot to go back to is
     * read on the input thread when the click's press runs, in order with everything sent before it, never from the caller's view of a
     * queue that may be behind (#153 review: a tap reading the X pointer while a trackpad move or an earlier tap was still queued left
     * the pointer parked on a cell). The move and the press run as one op, the release and the move back as another, and the release
     * keeps the hold even when both ran late. Sleeps [holdMs] on the caller (never the UI thread).
     */
    public void injectClickAt(int x, int y, Pointer.Button button, long holdMs) {
        final int[] back = new int[2];
        submitButtonPress(button, () -> {
            back[0] = pointer.getX(); back[1] = pointer.getY();
            pointerMoveNow(x, y);
        });
        try { Thread.sleep(holdMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        submitButtonRelease(button, () -> pointerMoveNow(back[0], back[1]));
    }

    /** a pointer press; [before] runs on the input thread just before it, in the same op */
    private void submitButtonPress(Pointer.Button button, Runnable before) {
        final int i = button.code() & 7;
        final long sent = System.nanoTime();
        submit(() -> { logicalButtons |= button.flag(); buttonSentNs[i] = sent; }, () -> {
            if (before != null) before.run();
            pointerButtonPressNow(button);
            buttonRanNs[i] = System.nanoTime();
        });
    }

    /** a pointer release, keeping the sender's hold; [after] runs on the input thread just after it, in the same op */
    private void submitButtonRelease(Pointer.Button button, Runnable after) {
        final int i = button.code() & 7;
        final long sent = System.nanoTime();
        final long[] pressSent = new long[1];
        submit(() -> { logicalButtons &= ~button.flag(); pressSent[0] = buttonSentNs[i]; }, () -> {
            keepHold(pressSent[0], sent, buttonRanNs[i]);
            pointerButtonReleaseNow(button);
            if (after != null) after.run();
        });
    }

    /**
     * On the input thread, before a release: the sender sent the press and the release [releaseSentNs] - [pressSentNs] apart; when the
     * queue ran behind, the two would run back to back (a secure cell acting on the release, a key tap, got no hold at all). Waits out
     * what is missing of that gap since the press RAN, up to [MAX_KEPT_HOLD_MS]; nothing when the queue kept up.
     */
    static void keepHold(long pressSentNs, long releaseSentNs, long pressRanNs) {
        long left = holdLeftNs(pressSentNs, releaseSentNs, pressRanNs, System.nanoTime());
        if (left <= 0L) return;
        try { Thread.sleep(left / 1_000_000L, (int) (left % 1_000_000L)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** what is left to wait of the sender's hold at [nowNs] (0: none; a press that has not run for this release, or 1 ms or less) */
    static long holdLeftNs(long pressSentNs, long releaseSentNs, long pressRanNs, long nowNs) {
        if (pressSentNs == 0L || pressRanNs == 0L || pressRanNs < pressSentNs || releaseSentNs < pressSentNs) return 0L;
        long want = Math.min(releaseSentNs - pressSentNs, MAX_KEPT_HOLD_MS * 1_000_000L);
        long left = want - (nowNs - pressRanNs);
        return left > 1_000_000L ? left : 0L;
    }

    public void injectKeyPress(XKeycode xKeycode) {
        injectKeyPress(xKeycode, 0);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym) {
        injectKeyPress(xKeycode, keysym, true);
    }

    public void injectKeyPress(XKeycode xKeycode, int keysym, boolean autoRepeat) {
        injectKeyPress(xKeycode, keysym, 0, autoRepeat);
    }

    /** As injectKeyPress, telling X what the key types without (keysym) and with Shift (shiftedKeysym). */
    public void injectKeyPress(XKeycode xKeycode, int keysym, int shiftedKeysym, boolean autoRepeat) {
        final int k = xKeycode.getId() & 0xff;
        final long sent = System.nanoTime();
        submit(() -> { logicalKeys[k] = true; keySentNs[k] = sent; }, () -> {
            keyPressNow(xKeycode, keysym, shiftedKeysym, autoRepeat);
            keyRanNs[k] = System.nanoTime();
        });
    }

    public void injectKeyRelease(XKeycode xKeycode) {
        final int k = xKeycode.getId() & 0xff;
        final long sent = System.nanoTime();
        final long[] pressSent = new long[1];
        submit(() -> { logicalKeys[k] = false; pressSent[0] = keySentNs[k]; }, () -> {
            keepHold(pressSent[0], sent, keyRanNs[k]);
            keyReleaseNow(xKeycode);
        });
    }

    /** a ReentrantLock that says which thread holds it (the slow-input line) */
    static final class OwnedLock extends ReentrantLock {
        Thread owner() { return getOwner(); }
    }

    /**
     * An input op has waited [waitedMs] (#153: the freeze reports could not say who held the X lock): the input thread's frames and
     * every held X lock's owner with its frames, logged once per slow op (WCXIN, and DiagLog's event for the report). On its own
     * short thread: the caller is whoever queued the next input, maybe the UI thread.
     */
    private void reportSlowInput(long waitedMs, Thread worker) {
        Thread t = new Thread(() -> {
            StringBuilder sb = new StringBuilder("input waited ").append(waitedMs).append(" ms: ");
            sb.append(frames(worker, 8));
            for (Lockable l : Lockable.values()) {
                ReentrantLock lock = locks.get(l);
                Thread owner = lock instanceof OwnedLock ? ((OwnedLock) lock).owner() : null;
                if (owner != null && owner != worker) {
                    sb.append(" | ").append(l.name()).append(" held by \"").append(owner.getName()).append("\": ").append(frames(owner, 10));
                }
            }
            String line = sb.toString();
            try { Log.w("WCXIN", line); } catch (Throwable ignored) {}
            try { com.jrees.wowcompanion.DiagLog.INSTANCE.event("xinput", line.length() > 1500 ? line.substring(0, 1500) : line, System.currentTimeMillis()); } catch (Throwable ignored) {}
        }, "WCXIN-report");
        t.setDaemon(true);
        t.start();
    }

    private static String frames(Thread t, int max) {
        StackTraceElement[] st = t.getStackTrace();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < st.length && i < max; i++) {
            if (i > 0) sb.append(" <- ");
            sb.append(st[i].getClassName().replaceFirst("^.*\\.", "")).append('.').append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
        }
        if (st.length > max) sb.append(" <- ...");
        return sb.toString();
    }

    private void pointerMoveNow(int x, int y) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setPosition(x, y);
        }
    }

    private void pointerMoveDeltaNow(int dx, int dy) {
        if (dx == 0 && dy == 0) return;

        WinHandler handler = winHandler;
        if (relativeMouseMovement && handler != null) {
            handler.mouseEvent(MouseEventFlags.MOVE, dx, dy, 0);
            return;
        }

        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            int minX = 0, minY = 0;
            int maxX = screenInfo.width - 1, maxY = screenInfo.height - 1;
            short clampedX = 0, clampedY = 0;

            // ClipCursor
            Rect confinement = grabManager.getConfinementBounds();
            if (confinement != null) {
                minX = Math.max(minX, confinement.left);
                minY = Math.max(minY, confinement.top);
                maxX = Math.min(maxX, confinement.right - 1);
                maxY = Math.min(maxY, confinement.bottom - 1);

                clampedX = (short) Mathf.clamp(pointer.getX() + dx, minX, maxX);
                clampedY = (short) Mathf.clamp(pointer.getY() + dy, minY, maxY);

                pointer.setPosition(clampedX, clampedY);
            } else {
                // Legacy path for old Proton builds with broken ClipCursor
                // Do NOT remove without confirming no regressions with:
                // - Games that rely on XWarpPointer (see WindowRequests.java:warpPointer)
                // - Games that call ClipCursor and happen to work well with this workaround
                short softMarginX = (short)(screenInfo.width * 0.05f);
                short softMarginY = (short)(screenInfo.height * 0.05f);
                short x = (short)Mathf.clamp(pointer.getX() + dx, -softMarginX, screenInfo.width - 1 + softMarginX);
                short y = (short)Mathf.clamp(pointer.getY() + dy, -softMarginY, screenInfo.height - 1 + softMarginY);

                pointer.setPosition(x, y);

                clampedX = x;
                clampedY = y;

                if (x < 0) {
                    clampedX = 0;
                }
                else if (x > screenInfo.width - 1) {
                    clampedX = (short) (screenInfo.width - 1);
                }
                if (y < 0) {
                    clampedY = 0;
                }
                else if (y > screenInfo.height - 1) {
                    clampedY = (short) (screenInfo.height - 1);
                }

                pointer.setX(clampedX);
                pointer.setY(clampedY);
            }

            XInput2Extension xi = getExtension(XInput2Extension.MAJOR_OPCODE);
            if (xi != null)
                xi.emitRawMotion(2, dx, dy);
        }
    }

    private void pointerButtonPressNow(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, true);

            XInput2Extension xi = getExtension(XInput2Extension.MAJOR_OPCODE);
            if (xi != null)
                xi.emitRawButton(2, buttonCode.code(), true);
        }
    }

    private void pointerButtonReleaseNow(Pointer.Button buttonCode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            pointer.setButton(buttonCode, false);

            XInput2Extension xi = getExtension(XInput2Extension.MAJOR_OPCODE);
            if (xi != null)
                xi.emitRawButton(2, buttonCode.code(), false);
        }
    }

    private void keyPressNow(XKeycode xKeycode, int keysym, int shiftedKeysym, boolean autoRepeat) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyPress(xKeycode.getId(), keysym, shiftedKeysym, autoRepeat);
        }
    }

    private void keyReleaseNow(XKeycode xKeycode) {
        try (XLock lock = lock(Lockable.WINDOW_MANAGER, Lockable.INPUT_DEVICE)) {
            keyboard.setKeyRelease(xKeycode.getId());
        }
    }

    private void registerExtension(Extension ext, int[] nextEventId, int[] nextErrorId) {
        if (ext.getNumEvents() > 0) {
            ext.setFirstEventId((byte) nextEventId[0]);
            nextEventId[0] += ext.getNumEvents();
        }
        if (ext.getNumErrors() > 0) {
            ext.setFirstErrorId((byte) nextErrorId[0]);
            nextErrorId[0] += ext.getNumErrors();
        }
        extensions.put(ext.getMajorOpcode(), ext);
    }

    private void setupExtensions() {
        int[] nextEventId = {64};
        int[] nextErrorId = {128};

        registerExtension(new BigReqExtension(),    nextEventId, nextErrorId);
        registerExtension(new MITSHMExtension(),    nextEventId, nextErrorId);
        registerExtension(new DRI3Extension(),      nextEventId, nextErrorId);
        registerExtension(new PresentExtension(),   nextEventId, nextErrorId);
        registerExtension(new SyncExtension(),      nextEventId, nextErrorId);
        if (supportsXInput2(runningFromGlibc)) {
            if (mouseDragCompatibilityEnabled) {
                registerExtension(new GenericEventExtension(), nextEventId, nextErrorId);
            }
            registerExtension(new XInput2Extension(),   nextEventId, nextErrorId);
        }
    }

    static boolean supportsXInput2(boolean useGlibcContainer) {
        return !useGlibcContainer;
    }

    static boolean shouldAdvertiseGenericEvents(boolean useGlibcContainer, boolean mouseDragCompatibilityEnabled) {
        return supportsXInput2(useGlibcContainer) && mouseDragCompatibilityEnabled;
    }

    public <T extends Extension> T getExtension(int opcode) {
        return (T)extensions.get(opcode);
    }

    public synchronized void setGrabbed(boolean grabbed, XClient client) {
        this.isGrabbed = grabbed;
        this.grabbingClient = client;
    }

    public synchronized boolean isGrabbedBy(XClient client) {
        if (this.isGrabbed) {
            return this.grabbingClient == client;
        }
        return false;
    }

}
