/*
 * DS Forever Streaming mode: the JNI glue between moonlight-common-c and com.jrees.wowcompanion.stream.MoonBridge.
 *
 * Derived from moonlight-android's app/src/main/jni/moonlight-core/callbacks.c and simplejni.c
 * (https://github.com/moonlight-stream/moonlight-android @ b48494cb96bff23d8886c4775cc4f39a1075495d, GPL-3.0,
 * Copyright (C) Moonlight Game Streaming Project and contributors). Changes for DS Forever: our Java class, a trimmed callback set
 * (no HDR metadata, motion, LEDs or trigger rumble: the Thor's pad has none of them), AES detection by getauxval instead of
 * cpufeatures (arm64-v8a only). Licensed GPL-3.0 like the rest of DS Forever.
 */
#include <jni.h>
#include <pthread.h>
#include <string.h>
#include <stdarg.h>
#include <sys/auxv.h>

#include <Limelight.h>
#include <opus_multistream.h>
#include <android/log.h>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#endif

#define BRIDGE_CLASS "com/jrees/wowcompanion/stream/MoonBridge"
#define JNI_FN(name) Java_com_jrees_wowcompanion_stream_MoonBridge_##name

static OpusMSDecoder* Decoder;
static OPUS_MULTISTREAM_CONFIGURATION OpusConfig;

static JavaVM* JVM;
static pthread_key_t JniEnvKey;
static pthread_once_t JniEnvKeyInitOnce = PTHREAD_ONCE_INIT;
static jclass GlobalBridgeClass;
static jmethodID DrSetup, DrStart, DrStop, DrCleanup, DrSubmit;
static jmethodID ArInit, ArStart, ArStop, ArCleanup, ArPlay;
static jmethodID ClStageStarting, ClStageComplete, ClStageFailed, ClStarted, ClTerminated, ClRumble, ClStatus;
static jbyteArray FrameBuffer;
static jshortArray AudioBuffer;

static void DetachThread(void* context) { (*JVM)->DetachCurrentThread(JVM); }
static void JniEnvKeyInit(void) { pthread_key_create(&JniEnvKey, DetachThread); }

static JNIEnv* GetThreadEnv(void) {
    JNIEnv* env;
    if ((*JVM)->GetEnv(JVM, (void**)&env, JNI_VERSION_1_4) == JNI_OK) return env;
    pthread_once(&JniEnvKeyInitOnce, JniEnvKeyInit);
    env = pthread_getspecific(JniEnvKey);
    if (env) return env;
    (*JVM)->AttachCurrentThread(JVM, &env, NULL);
    pthread_setspecific(JniEnvKey, env);
    return env;
}

/* a Java exception in a callback on a native thread: clear it (logged) rather than crash the stream */
static int Failed(JNIEnv* env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
        return 1;
    }
    return 0;
}

JNIEXPORT void JNICALL JNI_FN(init)(JNIEnv* env, jclass clazz) {
    (*env)->GetJavaVM(env, &JVM);
    GlobalBridgeClass = (*env)->NewGlobalRef(env, (*env)->FindClass(env, BRIDGE_CLASS));
    DrSetup = (*env)->GetStaticMethodID(env, clazz, "bridgeDrSetup", "(IIII)I");
    DrStart = (*env)->GetStaticMethodID(env, clazz, "bridgeDrStart", "()V");
    DrStop = (*env)->GetStaticMethodID(env, clazz, "bridgeDrStop", "()V");
    DrCleanup = (*env)->GetStaticMethodID(env, clazz, "bridgeDrCleanup", "()V");
    DrSubmit = (*env)->GetStaticMethodID(env, clazz, "bridgeDrSubmitDecodeUnit", "([BIIIIJ)I");
    ArInit = (*env)->GetStaticMethodID(env, clazz, "bridgeArInit", "(III)I");
    ArStart = (*env)->GetStaticMethodID(env, clazz, "bridgeArStart", "()V");
    ArStop = (*env)->GetStaticMethodID(env, clazz, "bridgeArStop", "()V");
    ArCleanup = (*env)->GetStaticMethodID(env, clazz, "bridgeArCleanup", "()V");
    ArPlay = (*env)->GetStaticMethodID(env, clazz, "bridgeArPlaySample", "([SI)V");
    ClStageStarting = (*env)->GetStaticMethodID(env, clazz, "bridgeClStageStarting", "(I)V");
    ClStageComplete = (*env)->GetStaticMethodID(env, clazz, "bridgeClStageComplete", "(I)V");
    ClStageFailed = (*env)->GetStaticMethodID(env, clazz, "bridgeClStageFailed", "(II)V");
    ClStarted = (*env)->GetStaticMethodID(env, clazz, "bridgeClConnectionStarted", "()V");
    ClTerminated = (*env)->GetStaticMethodID(env, clazz, "bridgeClConnectionTerminated", "(I)V");
    ClRumble = (*env)->GetStaticMethodID(env, clazz, "bridgeClRumble", "(SSS)V");
    ClStatus = (*env)->GetStaticMethodID(env, clazz, "bridgeClConnectionStatusUpdate", "(I)V");
}

/* ---------------------------------------------------------------- video: decode units to MediaCodec (Java) */
static int BridgeDrSetup(int videoFormat, int width, int height, int redrawRate, void* context, int drFlags) {
    JNIEnv* env = GetThreadEnv();
    int err = (*env)->CallStaticIntMethod(env, GlobalBridgeClass, DrSetup, videoFormat, width, height, redrawRate);
    if (Failed(env)) return -1;
    if (err != 0) return err;
    FrameBuffer = (*env)->NewGlobalRef(env, (*env)->NewByteArray(env, 32768));
    return 0;
}
static void BridgeDrStart(void) { JNIEnv* env = GetThreadEnv(); (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, DrStart); Failed(env); }
static void BridgeDrStop(void) { JNIEnv* env = GetThreadEnv(); (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, DrStop); Failed(env); }
static void BridgeDrCleanup(void) {
    JNIEnv* env = GetThreadEnv();
    if (FrameBuffer) { (*env)->DeleteGlobalRef(env, FrameBuffer); FrameBuffer = NULL; }
    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, DrCleanup); Failed(env);
}

static int Submit(JNIEnv* env, int length, int bufferType, PDECODE_UNIT du) {
    int ret = (*env)->CallStaticIntMethod(env, GlobalBridgeClass, DrSubmit, FrameBuffer, length, bufferType,
                                          du->frameNumber, du->frameType, (jlong)du->receiveTimeUs);
    if (Failed(env)) return DR_NEED_IDR;
    return ret;
}

static int BridgeDrSubmitDecodeUnit(PDECODE_UNIT du) {
    JNIEnv* env = GetThreadEnv();
    if ((*env)->GetArrayLength(env, FrameBuffer) < du->fullLength) {
        (*env)->DeleteGlobalRef(env, FrameBuffer);
        FrameBuffer = (*env)->NewGlobalRef(env, (*env)->NewByteArray(env, du->fullLength));
    }
    int offset = 0;
    for (PLENTRY e = du->bufferList; e != NULL; e = e->next) {
        if (e->bufferType != BUFFER_TYPE_PICDATA) {
            /* parameter sets (SPS / PPS / VPS) go to the decoder on their own, from the start of the buffer */
            (*env)->SetByteArrayRegion(env, FrameBuffer, 0, e->length, (jbyte*)e->data);
            int ret = Submit(env, e->length, e->bufferType, du);
            if (ret != DR_OK) return ret;
        } else {
            (*env)->SetByteArrayRegion(env, FrameBuffer, offset, e->length, (jbyte*)e->data);
            offset += e->length;
        }
    }
    return Submit(env, offset, BUFFER_TYPE_PICDATA, du);
}

/* ---------------------------------------------------------------- audio: Opus decoded here, PCM to AudioTrack (Java) */
static int BridgeArInit(int audioConfiguration, POPUS_MULTISTREAM_CONFIGURATION opusConfig, void* context, int flags) {
    JNIEnv* env = GetThreadEnv();
    int err = (*env)->CallStaticIntMethod(env, GlobalBridgeClass, ArInit, audioConfiguration, opusConfig->sampleRate, opusConfig->samplesPerFrame);
    if (Failed(env)) err = -1;
    if (err == 0) {
        memcpy(&OpusConfig, opusConfig, sizeof(*opusConfig));
        Decoder = opus_multistream_decoder_create(opusConfig->sampleRate, opusConfig->channelCount, opusConfig->streams,
                                                  opusConfig->coupledStreams, opusConfig->mapping, &err);
        if (Decoder == NULL) {
            (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ArCleanup); Failed(env);
            return -1;
        }
        AudioBuffer = (*env)->NewGlobalRef(env, (*env)->NewShortArray(env, opusConfig->channelCount * opusConfig->samplesPerFrame));
    }
    return err;
}
static void BridgeArStart(void) { JNIEnv* env = GetThreadEnv(); (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ArStart); Failed(env); }
static void BridgeArStop(void) { JNIEnv* env = GetThreadEnv(); (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ArStop); Failed(env); }
static void BridgeArCleanup(void) {
    JNIEnv* env = GetThreadEnv();
    if (Decoder) { opus_multistream_decoder_destroy(Decoder); Decoder = NULL; }
    if (AudioBuffer) { (*env)->DeleteGlobalRef(env, AudioBuffer); AudioBuffer = NULL; }
    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ArCleanup); Failed(env);
}
static void BridgeArDecodeAndPlaySample(char* sampleData, int sampleLength) {
    JNIEnv* env = GetThreadEnv();
    jshort* pcm = (*env)->GetPrimitiveArrayCritical(env, AudioBuffer, NULL);
    int frames = opus_multistream_decode(Decoder, (const unsigned char*)sampleData, sampleLength, pcm, OpusConfig.samplesPerFrame, 0);
    if (frames > 0) {
        (*env)->ReleasePrimitiveArrayCritical(env, AudioBuffer, pcm, 0);
        (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ArPlay, AudioBuffer, frames * OpusConfig.channelCount);
        Failed(env);
    } else {
        (*env)->ReleasePrimitiveArrayCritical(env, AudioBuffer, pcm, JNI_ABORT);
    }
}

/* ---------------------------------------------------------------- the connection's life */
static void BridgeClStageStarting(int stage) { JNIEnv* env = GetThreadEnv(); (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ClStageStarting, stage); Failed(env); }
static void BridgeClStageComplete(int stage) { JNIEnv* env = GetThreadEnv(); (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ClStageComplete, stage); Failed(env); }
static void BridgeClStageFailed(int stage, int errorCode) { JNIEnv* env = GetThreadEnv(); (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ClStageFailed, stage, errorCode); Failed(env); }
static void BridgeClConnectionStarted(void) { JNIEnv* env = GetThreadEnv(); (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ClStarted); Failed(env); }
static void BridgeClConnectionTerminated(int errorCode) { JNIEnv* env = GetThreadEnv(); (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ClTerminated, errorCode); Failed(env); }
static void BridgeClRumble(unsigned short controllerNumber, unsigned short low, unsigned short high) {
    JNIEnv* env = GetThreadEnv();
    (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ClRumble, (short)controllerNumber, (short)low, (short)high); Failed(env);
}
static void BridgeClConnectionStatusUpdate(int status) { JNIEnv* env = GetThreadEnv(); (*env)->CallStaticVoidMethod(env, GlobalBridgeClass, ClStatus, status); Failed(env); }
static void BridgeClLogMessage(const char* format, ...) {
    va_list va; va_start(va, format);
    __android_log_vprint(ANDROID_LOG_INFO, "DSFSTREAM-core", format, va);
    va_end(va);
}

static DECODER_RENDERER_CALLBACKS VideoCallbacks = {
    .setup = BridgeDrSetup, .start = BridgeDrStart, .stop = BridgeDrStop, .cleanup = BridgeDrCleanup,
    .submitDecodeUnit = BridgeDrSubmitDecodeUnit,
};
static AUDIO_RENDERER_CALLBACKS AudioCallbacks = {
    .init = BridgeArInit, .start = BridgeArStart, .stop = BridgeArStop, .cleanup = BridgeArCleanup,
    .decodeAndPlaySample = BridgeArDecodeAndPlaySample, .capabilities = CAPABILITY_SUPPORTS_ARBITRARY_AUDIO_DURATION,
};
static CONNECTION_LISTENER_CALLBACKS ConnCallbacks = {
    .stageStarting = BridgeClStageStarting, .stageComplete = BridgeClStageComplete, .stageFailed = BridgeClStageFailed,
    .connectionStarted = BridgeClConnectionStarted, .connectionTerminated = BridgeClConnectionTerminated,
    .logMessage = BridgeClLogMessage, .rumble = BridgeClRumble, .connectionStatusUpdate = BridgeClConnectionStatusUpdate,
};

static int HasFastAes(void) {
#if defined(__aarch64__)
    return (getauxval(AT_HWCAP) & HWCAP_AES) != 0;
#else
    return 0;
#endif
}

JNIEXPORT jint JNICALL JNI_FN(startConnection)(JNIEnv* env, jclass clazz,
        jstring address, jstring appVersion, jstring gfeVersion, jstring rtspSessionUrl, jint serverCodecModeSupport,
        jint width, jint height, jint fps, jint bitrate, jint packetSize, jint streamingRemotely,
        jint audioConfiguration, jint supportedVideoFormats, jint clientRefreshRateX100,
        jbyteArray riAesKey, jbyteArray riAesIv, jint videoCapabilities, jint colorSpace, jint colorRange) {
    SERVER_INFORMATION serverInfo = {
        .address = (*env)->GetStringUTFChars(env, address, 0),
        .serverInfoAppVersion = (*env)->GetStringUTFChars(env, appVersion, 0),
        .serverInfoGfeVersion = gfeVersion ? (*env)->GetStringUTFChars(env, gfeVersion, 0) : NULL,
        .rtspSessionUrl = rtspSessionUrl ? (*env)->GetStringUTFChars(env, rtspSessionUrl, 0) : NULL,
        .serverCodecModeSupport = serverCodecModeSupport,
    };
    STREAM_CONFIGURATION streamConfig = {
        .width = width, .height = height, .fps = fps, .bitrate = bitrate, .packetSize = packetSize,
        .streamingRemotely = streamingRemotely, .audioConfiguration = audioConfiguration,
        .supportedVideoFormats = supportedVideoFormats, .clientRefreshRateX100 = clientRefreshRateX100,
        .encryptionFlags = HasFastAes() ? ENCFLG_ALL : ENCFLG_AUDIO,
        .colorSpace = colorSpace, .colorRange = colorRange,
    };
    jbyte* key = (*env)->GetByteArrayElements(env, riAesKey, NULL);
    memcpy(streamConfig.remoteInputAesKey, key, sizeof(streamConfig.remoteInputAesKey));
    (*env)->ReleaseByteArrayElements(env, riAesKey, key, JNI_ABORT);
    jbyte* iv = (*env)->GetByteArrayElements(env, riAesIv, NULL);
    memcpy(streamConfig.remoteInputAesIv, iv, sizeof(streamConfig.remoteInputAesIv));
    (*env)->ReleaseByteArrayElements(env, riAesIv, iv, JNI_ABORT);
    VideoCallbacks.capabilities = videoCapabilities;

    int ret = LiStartConnection(&serverInfo, &streamConfig, &ConnCallbacks, &VideoCallbacks, &AudioCallbacks, NULL, 0, NULL, 0);

    (*env)->ReleaseStringUTFChars(env, address, serverInfo.address);
    (*env)->ReleaseStringUTFChars(env, appVersion, serverInfo.serverInfoAppVersion);
    if (gfeVersion) (*env)->ReleaseStringUTFChars(env, gfeVersion, serverInfo.serverInfoGfeVersion);
    if (rtspSessionUrl) (*env)->ReleaseStringUTFChars(env, rtspSessionUrl, serverInfo.rtspSessionUrl);
    return ret;
}

JNIEXPORT void JNICALL JNI_FN(stopConnection)(JNIEnv* env, jclass clazz) { LiStopConnection(); }
JNIEXPORT void JNICALL JNI_FN(interruptConnection)(JNIEnv* env, jclass clazz) { LiInterruptConnection(); }
JNIEXPORT jstring JNICALL JNI_FN(getStageName)(JNIEnv* env, jclass clazz, jint stage) { return (*env)->NewStringUTF(env, LiGetStageName(stage)); }
JNIEXPORT jstring JNICALL JNI_FN(getLaunchUrlQueryParameters)(JNIEnv* env, jclass clazz) { return (*env)->NewStringUTF(env, LiGetLaunchUrlQueryParameters()); }
JNIEXPORT jint JNICALL JNI_FN(getPendingVideoFrames)(JNIEnv* env, jclass clazz) { return LiGetPendingVideoFrames(); }
JNIEXPORT jint JNICALL JNI_FN(getPortFlagsFromTerminationErrorCode)(JNIEnv* env, jclass clazz, jint errorCode) { return LiGetPortFlagsFromTerminationErrorCode(errorCode); }

/* ---------------------------------------------------------------- input: keyboard, mouse, the Xbox pad */
JNIEXPORT void JNICALL JNI_FN(sendKeyboardInput)(JNIEnv* env, jclass clazz, jshort keyCode, jbyte keyAction, jbyte modifiers, jbyte flags) {
    LiSendKeyboardEvent2(keyCode, keyAction, modifiers, flags);
}
JNIEXPORT void JNICALL JNI_FN(sendMousePosition)(JNIEnv* env, jclass clazz, jshort x, jshort y, jshort refW, jshort refH) {
    LiSendMousePositionEvent(x, y, refW, refH);
}
JNIEXPORT void JNICALL JNI_FN(sendMouseMove)(JNIEnv* env, jclass clazz, jshort dx, jshort dy) { LiSendMouseMoveEvent(dx, dy); }
JNIEXPORT void JNICALL JNI_FN(sendMouseButton)(JNIEnv* env, jclass clazz, jbyte action, jbyte button) { LiSendMouseButtonEvent(action, button); }
JNIEXPORT void JNICALL JNI_FN(sendMultiControllerInput)(JNIEnv* env, jclass clazz, jshort controllerNumber, jshort activeGamepadMask,
        jint buttonFlags, jbyte leftTrigger, jbyte rightTrigger, jshort leftStickX, jshort leftStickY, jshort rightStickX, jshort rightStickY) {
    LiSendMultiControllerEvent(controllerNumber, activeGamepadMask, buttonFlags, leftTrigger, rightTrigger,
                               leftStickX, leftStickY, rightStickX, rightStickY);
}
JNIEXPORT jint JNICALL JNI_FN(sendControllerArrivalEvent)(JNIEnv* env, jclass clazz, jbyte controllerNumber, jshort activeGamepadMask,
        jbyte type, jint supportedButtonFlags, jshort capabilities) {
    return LiSendControllerArrivalEvent(controllerNumber, activeGamepadMask, type, supportedButtonFlags, capabilities);
}
