# DS Forever Streaming mode: libdsf-moonlight.so = moonlight-common-c + enet + nanors + our JNI glue (dsf/), arm64-v8a.
# Follows moonlight-android's app/src/main/jni/moonlight-core/Android.mk (b48494cb). Prebuilt OpenSSL / Opus come from
# moonlight-android too (tools/stream/vendor_moonlight.sh puts them under prebuilt/, git-ignored).
MY_LOCAL_PATH := $(call my-dir)
LOCAL_PATH := $(MY_LOCAL_PATH)

include $(CLEAR_VARS)
LOCAL_MODULE := dsf-crypto
LOCAL_SRC_FILES := prebuilt/openssl/$(TARGET_ARCH_ABI)/libcrypto.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/prebuilt/openssl/include
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := dsf-opus
LOCAL_SRC_FILES := prebuilt/opus/$(TARGET_ARCH_ABI)/libopus.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/prebuilt/opus/include
include $(PREBUILT_STATIC_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := dsf-moonlight
MC := moonlight-common-c
LOCAL_SRC_FILES := $(MC)/src/AudioStream.c \
                   $(MC)/src/ByteBuffer.c \
                   $(MC)/src/Connection.c \
                   $(MC)/src/ConnectionTester.c \
                   $(MC)/src/ControlStream.c \
                   $(MC)/src/FakeCallbacks.c \
                   $(MC)/src/InputStream.c \
                   $(MC)/src/LinkedBlockingQueue.c \
                   $(MC)/src/Misc.c \
                   $(MC)/src/Platform.c \
                   $(MC)/src/PlatformCrypto.c \
                   $(MC)/src/PlatformSockets.c \
                   $(MC)/src/RtpAudioQueue.c \
                   $(MC)/src/RtpVideoQueue.c \
                   $(MC)/src/RtspConnection.c \
                   $(MC)/src/RtspParser.c \
                   $(MC)/src/SdpGenerator.c \
                   $(MC)/src/SimpleStun.c \
                   $(MC)/src/VideoDepacketizer.c \
                   $(MC)/src/VideoStream.c \
                   $(MC)/nanors/deps/obl/oblas_common.c \
                   $(MC)/nanors/deps/obl/oblas_lite.c \
                   $(MC)/nanors/rs.c \
                   $(MC)/enet/callbacks.c \
                   $(MC)/enet/compress.c \
                   $(MC)/enet/host.c \
                   $(MC)/enet/list.c \
                   $(MC)/enet/packet.c \
                   $(MC)/enet/peer.c \
                   $(MC)/enet/protocol.c \
                   $(MC)/enet/unix.c \
                   $(MC)/enet/win32.c \
                   dsf/dsf_bridge.c

LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(MC)/enet/include \
                    $(LOCAL_PATH)/$(MC)/nanors/deps/obl \
                    $(LOCAL_PATH)/$(MC)/nanors \
                    $(LOCAL_PATH)/$(MC)/src

LOCAL_CFLAGS := -DHAS_SOCKLEN_T=1 -DLC_ANDROID -DHAVE_CLOCK_GETTIME=1 -O2
LOCAL_LDLIBS := -llog
LOCAL_STATIC_LIBRARIES := dsf-opus dsf-crypto
LOCAL_LDFLAGS += -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384
LOCAL_BRANCH_PROTECTION := standard
include $(BUILD_SHARED_LIBRARY)
