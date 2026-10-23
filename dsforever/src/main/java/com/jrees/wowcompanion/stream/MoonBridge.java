package com.jrees.wowcompanion.stream;

import android.util.Log;

/**
 * The Java side of Moonlight's core (libdsf-moonlight.so: moonlight-common-c + src/main/jni/dsf/dsf_bridge.c). Modelled on
 * moonlight-android's com.limelight.nvstream.jni.MoonBridge (GPL-3.0, b48494cb): the native code calls the bridge* methods below from
 * its own threads; they hand each call to the renderer / listener set with {@link #setup} before a connection starts.
 * {@link #loaded} is false in a build without the native core (tools/stream/vendor_moonlight.sh not run): Streaming then says so.
 */
public final class MoonBridge {
    private MoonBridge() {}

    public interface Video {
        int setup(int videoFormat, int width, int height, int fps);
        void start();
        void stop();
        void cleanup();
        /** one buffer (a parameter set, or a whole frame's picture data); DR_OK or DR_NEED_IDR */
        int submit(byte[] data, int length, int bufferType, int frameNumber, int frameType, long receiveTimeUs);
    }

    public interface Audio {
        int init(int audioConfiguration, int sampleRate, int samplesPerFrame);
        void start();
        void stop();
        void cleanup();
        /** [samples] interleaved PCM-16 values */
        void play(short[] pcm, int samples);
    }

    public interface Listener {
        void stageStarting(int stage);
        void stageComplete(int stage);
        void stageFailed(int stage, int errorCode);
        void connectionStarted();
        void connectionTerminated(int errorCode);
        void rumble(int controller, int lowFreq, int highFreq);
        void connectionStatus(int status);
    }

    public static final int DR_OK = 0;
    public static final int DR_NEED_IDR = -1;
    public static final int BUFFER_TYPE_PICDATA = 0;
    public static final int BUFFER_TYPE_SPS = 1;
    public static final int BUFFER_TYPE_PPS = 2;
    public static final int BUFFER_TYPE_VPS = 3;
    public static final int FRAME_TYPE_IDR = 1;
    public static final int VIDEO_FORMAT_MASK_H265 = 0x0F00;
    public static final int VIDEO_FORMAT_MASK_AV1 = 0xF000;
    public static final int CONN_STATUS_POOR = 1;
    public static final int ML_ERROR_GRACEFUL_TERMINATION = 0;
    public static final int ML_ERROR_NO_VIDEO_TRAFFIC = -100;
    public static final int ML_ERROR_NO_VIDEO_FRAME = -101;
    /** MAKE_AUDIO_CONFIGURATION(2, 0x3) */
    public static final int AUDIO_CONFIGURATION_STEREO = (0x3 << 16) | (2 << 8) | 0xCA;
    /** the launch URL's surroundAudioInfo for stereo: channelMask << 16 | channelCount */
    public static final int SURROUND_INFO_STEREO = (0x3 << 16) | 2;
    public static final int COLORSPACE_REC_709 = 1;
    public static final int COLOR_RANGE_LIMITED = 0;
    public static final int CAPABILITY_DIRECT_SUBMIT = 1;
    public static final byte KEY_ACTION_DOWN = 0x03;
    public static final byte KEY_ACTION_UP = 0x04;
    public static final byte BUTTON_ACTION_PRESS = 0x07;
    public static final byte BUTTON_ACTION_RELEASE = 0x08;
    public static final byte LI_CTYPE_XBOX = 0x01;
    public static final short LI_CCAP_ANALOG_TRIGGERS = 0x01;
    public static final short LI_CCAP_RUMBLE = 0x02;

    private static volatile Video video;
    private static volatile Audio audio;
    private static volatile Listener listener;

    public static final boolean loaded;
    static {
        boolean ok = false;
        try { System.loadLibrary("dsf-moonlight"); init(); ok = true; }
        catch (Throwable t) { try { Log.w("DSFSTREAM", "no streaming core in this build: " + t); } catch (Throwable ignored) {} }
        loaded = ok;
    }

    public static void setup(Video v, Audio a, Listener l) { video = v; audio = a; listener = l; }
    public static void clear() { video = null; audio = null; listener = null; }

    // ---------------------------------------------------------------- called from native threads
    static int bridgeDrSetup(int videoFormat, int width, int height, int fps) { Video v = video; return v == null ? -1 : v.setup(videoFormat, width, height, fps); }
    static void bridgeDrStart() { Video v = video; if (v != null) v.start(); }
    static void bridgeDrStop() { Video v = video; if (v != null) v.stop(); }
    static void bridgeDrCleanup() { Video v = video; if (v != null) v.cleanup(); }
    static int bridgeDrSubmitDecodeUnit(byte[] data, int length, int bufferType, int frameNumber, int frameType, long receiveTimeUs) {
        Video v = video; return v == null ? DR_OK : v.submit(data, length, bufferType, frameNumber, frameType, receiveTimeUs);
    }
    static int bridgeArInit(int audioConfiguration, int sampleRate, int samplesPerFrame) { Audio a = audio; return a == null ? -1 : a.init(audioConfiguration, sampleRate, samplesPerFrame); }
    static void bridgeArStart() { Audio a = audio; if (a != null) a.start(); }
    static void bridgeArStop() { Audio a = audio; if (a != null) a.stop(); }
    static void bridgeArCleanup() { Audio a = audio; if (a != null) a.cleanup(); }
    static void bridgeArPlaySample(short[] pcm, int samples) { Audio a = audio; if (a != null) a.play(pcm, samples); }
    static void bridgeClStageStarting(int stage) { Listener l = listener; if (l != null) l.stageStarting(stage); }
    static void bridgeClStageComplete(int stage) { Listener l = listener; if (l != null) l.stageComplete(stage); }
    static void bridgeClStageFailed(int stage, int errorCode) { Listener l = listener; if (l != null) l.stageFailed(stage, errorCode); }
    static void bridgeClConnectionStarted() { Listener l = listener; if (l != null) l.connectionStarted(); }
    static void bridgeClConnectionTerminated(int errorCode) { Listener l = listener; if (l != null) l.connectionTerminated(errorCode); }
    static void bridgeClRumble(short controller, short low, short high) { Listener l = listener; if (l != null) l.rumble(controller, low & 0xFFFF, high & 0xFFFF); }
    static void bridgeClConnectionStatusUpdate(int status) { Listener l = listener; if (l != null) l.connectionStatus(status); }

    // ---------------------------------------------------------------- native
    private static native void init();
    public static native int startConnection(String address, String appVersion, String gfeVersion, String rtspSessionUrl,
                                             int serverCodecModeSupport, int width, int height, int fps, int bitrate, int packetSize,
                                             int streamingRemotely, int audioConfiguration, int supportedVideoFormats,
                                             int clientRefreshRateX100, byte[] riAesKey, byte[] riAesIv, int videoCapabilities,
                                             int colorSpace, int colorRange);
    public static native void stopConnection();
    public static native void interruptConnection();
    public static native String getStageName(int stage);
    public static native String getLaunchUrlQueryParameters();
    public static native int getPendingVideoFrames();
    public static native int getPortFlagsFromTerminationErrorCode(int errorCode);
    public static native void sendKeyboardInput(short keyCode, byte keyAction, byte modifiers, byte flags);
    public static native void sendMousePosition(short x, short y, short referenceWidth, short referenceHeight);
    public static native void sendMouseMove(short deltaX, short deltaY);
    public static native void sendMouseButton(byte buttonEvent, byte mouseButton);
    public static native void sendMouseHighResScroll(short scrollAmount);
    public static native void sendMultiControllerInput(short controllerNumber, short activeGamepadMask, int buttonFlags,
                                                       byte leftTrigger, byte rightTrigger, short leftStickX, short leftStickY,
                                                       short rightStickX, short rightStickY);
    public static native int sendControllerArrivalEvent(byte controllerNumber, short activeGamepadMask, byte type,
                                                        int supportedButtonFlags, short capabilities);

    /** {@link MoonInput} over the native keyboard / mouse channel (keys go as 0x80xx: moonlight-android's KEY_PREFIX) */
    public static final MoonInput INPUT = new MoonInput() {
        @Override public void key(int vk, boolean down, int mods) {
            if (!loaded) return;
            sendKeyboardInput((short) (0x8000 | (vk & 0xFF)), down ? KEY_ACTION_DOWN : KEY_ACTION_UP, (byte) mods, (byte) 0);
        }
        @Override public void mousePosition(int x, int y, int refW, int refH) { if (loaded) sendMousePosition((short) x, (short) y, (short) refW, (short) refH); }
        @Override public void mouseMove(int dx, int dy) { if (loaded) sendMouseMove((short) dx, (short) dy); }
        @Override public void mouseButton(boolean down, int button) { if (loaded) sendMouseButton(down ? BUTTON_ACTION_PRESS : BUTTON_ACTION_RELEASE, (byte) button); }
        @Override public void scroll(int amount) { if (loaded) sendMouseHighResScroll((short) Math.max(-32768, Math.min(32767, amount))); }
    };
}
