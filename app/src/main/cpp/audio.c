#include "audio.h"

#include <aaudio/AAudio.h>
#include <android/log.h>
#include <stdatomic.h>
#include <string.h>

#define LOG_TAG "PrismAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Ring capacity in stereo frames (power of two). 8192 frames ≈ 170 ms at 48 kHz,
// far more than the target fill, so overflow only happens if the consumer stalls.
#define RING_FRAMES 8192u
#define RING_MASK (RING_FRAMES - 1u)

static int16_t ring[RING_FRAMES * 2];
static _Atomic uint32_t writePos;
static _Atomic uint32_t readPos;

static AAudioStream* stream;
static int sampleRate = 48000;
static int32_t framesPerBurst = 192;
static size_t targetFill = 1600;
static _Atomic int gainQ15 = 32767;
static _Atomic bool disconnected;
static _Atomic bool wantRunning;
static _Atomic uint32_t underruns;
static _Atomic bool flushRequested;

// Last emitted frame, used to fade towards silence on underrun instead of
// dropping straight to zero (which produces an audible click).
static int16_t lastL, lastR;

static aaudio_data_callback_result_t dataCallback(AAudioStream* s, void* user, void* audioData, int32_t numFrames) {
    (void) s;
    (void) user;
    int16_t* out = audioData;
    uint32_t r = atomic_load_explicit(&readPos, memory_order_relaxed);
    uint32_t w = atomic_load_explicit(&writePos, memory_order_acquire);
    if (atomic_exchange_explicit(&flushRequested, false, memory_order_acq_rel)) {
        r = w;
    }
    uint32_t avail = w - r;
    int gain = atomic_load_explicit(&gainQ15, memory_order_relaxed);

    int32_t n = (int32_t) (avail < (uint32_t) numFrames ? avail : (uint32_t) numFrames);
    for (int32_t i = 0; i < n; ++i) {
        uint32_t idx = ((r + i) & RING_MASK) * 2;
        out[i * 2] = (int16_t) ((ring[idx] * gain) >> 15);
        out[i * 2 + 1] = (int16_t) ((ring[idx + 1] * gain) >> 15);
    }
    if (n > 0) {
        lastL = out[(n - 1) * 2];
        lastR = out[(n - 1) * 2 + 1];
    }
    if (n < numFrames) {
        atomic_fetch_add_explicit(&underruns, 1, memory_order_relaxed);
        for (int32_t i = n; i < numFrames; ++i) {
            lastL = (int16_t) (lastL * 15 / 16);
            lastR = (int16_t) (lastR * 15 / 16);
            out[i * 2] = lastL;
            out[i * 2 + 1] = lastR;
        }
    }
    atomic_store_explicit(&readPos, r + (uint32_t) n, memory_order_release);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void errorCallback(AAudioStream* s, void* user, aaudio_result_t error) {
    (void) s;
    (void) user;
    LOGW("AAudio error %d (%s)", error, AAudio_convertResultToText(error));
    atomic_store(&disconnected, true);
}

static bool openStream(void) {
    AAudioStreamBuilder* builder;
    if (AAudio_createStreamBuilder(&builder) != AAUDIO_OK) {
        return false;
    }
    AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    AAudioStreamBuilder_setChannelCount(builder, 2);
    AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    if (__builtin_available(android 28, *)) {
        AAudioStreamBuilder_setUsage(builder, AAUDIO_USAGE_GAME);
        AAudioStreamBuilder_setContentType(builder, AAUDIO_CONTENT_TYPE_MUSIC);
    }
    AAudioStreamBuilder_setDataCallback(builder, dataCallback, NULL);
    AAudioStreamBuilder_setErrorCallback(builder, errorCallback, NULL);
    aaudio_result_t res = AAudioStreamBuilder_openStream(builder, &stream);
    AAudioStreamBuilder_delete(builder);
    if (res != AAUDIO_OK) {
        LOGW("openStream failed: %s", AAudio_convertResultToText(res));
        stream = NULL;
        return false;
    }
    sampleRate = AAudioStream_getSampleRate(stream);
    framesPerBurst = AAudioStream_getFramesPerBurst(stream);
    // Two bursts in the device buffer is the usual low-latency sweet spot.
    AAudioStream_setBufferSizeInFrames(stream, framesPerBurst * 2);
    // Keep roughly 25 ms queued on our side (at least 4 bursts) to absorb
    // frame-time jitter on the emulation thread.
    size_t minFill = (size_t) framesPerBurst * 4;
    size_t jitter = (size_t) sampleRate / 40;
    targetFill = minFill > jitter ? minFill : jitter;
    LOGI("AAudio open: %d Hz, burst %d, target fill %zu", sampleRate, framesPerBurst, targetFill);
    atomic_store(&disconnected, false);
    return true;
}

bool audio_open(void) {
    if (stream) {
        return true;
    }
    audio_flush();
    if (!openStream()) {
        return false;
    }
    atomic_store(&wantRunning, true);
    AAudioStream_requestStart(stream);
    return true;
}

void audio_close(void) {
    atomic_store(&wantRunning, false);
    if (stream) {
        AAudioStream_requestStop(stream);
        AAudioStream_close(stream);
        stream = NULL;
    }
}

void audio_pause(void) {
    atomic_store(&wantRunning, false);
    if (stream) {
        AAudioStream_requestPause(stream);
    }
    audio_flush();
}

void audio_resume(void) {
    atomic_store(&wantRunning, true);
    audio_flush();
    if (stream) {
        AAudioStream_requestStart(stream);
    }
}

void audio_service(void) {
    if (!atomic_load(&disconnected) || !stream) {
        return;
    }
    LOGI("Re-opening disconnected audio stream");
    AAudioStream_close(stream);
    stream = NULL;
    if (openStream() && atomic_load(&wantRunning)) {
        audio_flush();
        AAudioStream_requestStart(stream);
    }
}

void audio_push(const int16_t* stereo, size_t frames) {
    uint32_t w = atomic_load_explicit(&writePos, memory_order_relaxed);
    uint32_t r = atomic_load_explicit(&readPos, memory_order_acquire);
    uint32_t space = RING_FRAMES - (w - r);
    if (frames > space) {
        frames = space; // drop the overflow rather than block the emulator
    }
    for (size_t i = 0; i < frames; ++i) {
        uint32_t idx = ((w + (uint32_t) i) & RING_MASK) * 2;
        ring[idx] = stereo[i * 2];
        ring[idx + 1] = stereo[i * 2 + 1];
    }
    atomic_store_explicit(&writePos, w + (uint32_t) frames, memory_order_release);
}

void audio_flush(void) {
    // readPos is owned by the consumer, so the flush is performed there.
    atomic_store_explicit(&flushRequested, true, memory_order_release);
}

int audio_sample_rate(void) {
    return sampleRate;
}

size_t audio_fill(void) {
    if (atomic_load(&flushRequested)) {
        return 0;
    }
    return atomic_load(&writePos) - atomic_load(&readPos);
}

size_t audio_target_fill(void) {
    return targetFill;
}

void audio_set_volume(float volume) {
    if (volume < 0) volume = 0;
    if (volume > 1) volume = 1;
    atomic_store(&gainQ15, (int) (volume * 32767.0f));
}

uint32_t audio_underruns(void) {
    return atomic_load(&underruns);
}
