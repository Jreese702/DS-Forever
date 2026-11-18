// DS Forever's offline voice typing: whisper.cpp (MIT, vendored at v1.9.5 in ../whisper.cpp) behind five JNI calls
// (com.jrees.wowcompanion.WhisperNative). Kotlin calls in; this code never calls back into Java (no GetMethodID, nothing R8 can strip
// but the natives themselves, which the keep rules and apk_jni_check.py cover). Nothing heard is ever logged: whisper's own log is off.
#include <jni.h>
#include <atomic>
#include <cstdint>
#include <string>
#include <vector>
#include "whisper.h"

namespace {

struct Handle {
    whisper_context* ctx = nullptr;
    std::atomic<bool> abort{false};
};

void quiet(enum ggml_log_level, const char*, void*) {}

bool aborted(void* user) { return static_cast<Handle*>(user)->abort.load(); }

Handle* of(jlong h) { return reinterpret_cast<Handle*>(static_cast<intptr_t>(h)); }

std::string str(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* c = env->GetStringUTFChars(s, nullptr);
    if (c == nullptr) return std::string();
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

// Modified UTF-8 for NewStringUTF: whisper's text is UTF-8; a 4-byte sequence (an emoji) would be invalid there, so it is dropped,
// and a broken sequence (a token cut inside a character) too
std::string jniSafe(const std::string& s) {
    std::string out;
    out.reserve(s.size());
    for (size_t i = 0; i < s.size();) {
        unsigned char c = static_cast<unsigned char>(s[i]);
        size_t n = c < 0x80 ? 1 : (c >> 5) == 0x6 ? 2 : (c >> 4) == 0xE ? 3 : (c >> 3) == 0x1E ? 4 : 0;
        if (n == 0 || i + n > s.size()) { i++; continue; }
        bool ok = true;
        for (size_t k = 1; k < n; k++) if ((static_cast<unsigned char>(s[i + k]) >> 6) != 0x2) ok = false;
        if (ok && n < 4 && !(n == 1 && c == 0)) out.append(s, i, n);
        i += ok ? n : 1;
    }
    return out;
}

// ptt-speed: the encoder's window from the clip, not whisper's whole 30 s (1500 = 30 s, 50 a second). A push-to-talk clip is a few
// seconds, so encoding 30 s was most of the time (PC bench, base.en q5_1, 4 threads: ~570 ms -> ~160 ms a clip). whisper.cpp's caveat:
// too short a window can repeat or drop words, so the clip's own length gets a MARGIN and a FLOOR, sized on the bench (8 clips, 2-12 s,
// all four models: the same words as the full window; a 256 floor repeated one sentence, 384 changed a word in the 12 s clip).
// 0 = the full window (a clip near 30 s).
int audioCtx(int samples) {
    const int kFull = 1500, kMargin = 192, kFloor = 448;
    long long c = (static_cast<long long>(samples) * 50 + 15999) / 16000 + kMargin;
    if (c < kFloor) c = kFloor;
    return c >= kFull ? 0 : static_cast<int>(c);
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_com_jrees_wowcompanion_WhisperNative_version(JNIEnv* env, jclass) {
    std::string v = std::string("whisper.cpp ") + whisper_version() + " | " + whisper_print_system_info();
    return env->NewStringUTF(jniSafe(v).c_str());
}

JNIEXPORT jlong JNICALL Java_com_jrees_wowcompanion_WhisperNative_load(JNIEnv* env, jclass, jstring path) {
    whisper_log_set(quiet, nullptr);
    std::string p = str(env, path);
    if (p.empty()) return 0;
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    // ptt-speed (the user, 2026-11-18: "the time between sending a PTT from voice and it actually sending in chat seems really high"):
    // flash attention on the CPU backend (ggml_flash_attn_ext, f16 on NEON): the PC bench ran the same clips ~1.4x faster, the words the same
    cp.flash_attn = true;
    whisper_context* ctx = whisper_init_from_file_with_params(p.c_str(), cp);
    if (ctx == nullptr) return 0;
    Handle* h = new Handle();
    h->ctx = ctx;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(h));
}

JNIEXPORT jstring JNICALL Java_com_jrees_wowcompanion_WhisperNative_transcribe(JNIEnv* env, jclass, jlong handle, jshortArray pcm,
                                                                                 jint count, jstring lang, jint threads, jstring prompt) {
    Handle* h = of(handle);
    if (h == nullptr || h->ctx == nullptr || pcm == nullptr) return nullptr;
    jsize len = env->GetArrayLength(pcm);
    int n = count < 0 ? 0 : (count > len ? len : count);
    if (n <= 0) return nullptr;
    std::vector<float> audio(static_cast<size_t>(n));
    {
        jshort* s = env->GetShortArrayElements(pcm, nullptr);
        if (s == nullptr) return nullptr;
        for (int i = 0; i < n; i++) audio[static_cast<size_t>(i)] = static_cast<float>(s[i]) / 32768.0f;
        env->ReleaseShortArrayElements(pcm, s, JNI_ABORT);
    }
    std::string language = str(env, lang);
    std::string initial = str(env, prompt);
    h->abort.store(false);

    whisper_full_params fp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    fp.n_threads = threads > 0 ? threads : 4;
    fp.language = language.empty() ? "en" : language.c_str();
    fp.detect_language = false;
    fp.translate = false;
    fp.no_context = true;
    fp.no_timestamps = true;
    fp.single_segment = true;
    fp.print_special = false;
    fp.print_progress = false;
    fp.print_realtime = false;
    fp.print_timestamps = false;
    fp.suppress_blank = true;
    fp.suppress_nst = true;
    fp.initial_prompt = initial.empty() ? nullptr : initial.c_str();
    fp.audio_ctx = audioCtx(n);
    fp.abort_callback = aborted;
    fp.abort_callback_user_data = h;

    if (whisper_full(h->ctx, fp, audio.data(), n) != 0 || h->abort.load()) return nullptr;
    std::string text;
    int segs = whisper_full_n_segments(h->ctx);
    for (int i = 0; i < segs; i++) {
        const char* t = whisper_full_get_segment_text(h->ctx, i);
        if (t != nullptr) text += t;
    }
    return env->NewStringUTF(jniSafe(text).c_str());
}

JNIEXPORT void JNICALL Java_com_jrees_wowcompanion_WhisperNative_abort(JNIEnv*, jclass, jlong handle) {
    Handle* h = of(handle);
    if (h != nullptr) h->abort.store(true);
}

JNIEXPORT void JNICALL Java_com_jrees_wowcompanion_WhisperNative_free(JNIEnv*, jclass, jlong handle) {
    Handle* h = of(handle);
    if (h == nullptr) return;
    if (h->ctx != nullptr) whisper_free(h->ctx);
    delete h;
}

}  // extern "C"
