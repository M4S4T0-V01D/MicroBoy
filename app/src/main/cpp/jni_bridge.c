// JNI bridge between the Kotlin frontend (dev.m4s4t0.microboy.emulation.NativeCore)
// and the mGBA core.
//
// Threading contract: every function that touches `core` is called from the
// single emulation thread owned by EmulatorSession. The only exception is
// nativeCopyFrame, which is called from the GL thread and only touches the
// mutex-protected front buffer.
#include <mgba/flags.h>

#include <mgba/core/blip_buf.h>
#include <mgba/core/core.h>
#include <mgba/core/log.h>
#include <mgba/core/serialize.h>
#include <mgba/gba/core.h>
#include <mgba/internal/gba/gba.h>
#include <mgba-util/vfs.h>

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

#include "audio.h"

#define LOG_TAG "MicroBoyCore"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#define JNI(name) Java_dev_m4s4t0_microboy_emulation_NativeCore_##name

#define WIDTH GBA_VIDEO_HORIZONTAL_PIXELS
#define HEIGHT GBA_VIDEO_VERTICAL_PIXELS
#define PIXELS (WIDTH * HEIGHT)

// Maximum deviation of the resampling ratio used for dynamic rate control.
// 0.5% is inaudible as pitch change but easily absorbs clock drift.
#define DRC_MAX_DELTA 0.005

static struct mCore* core;
static bool romLoaded;

static color_t backBuffer[PIXELS];
static uint32_t frontBuffer[PIXELS];
static pthread_mutex_t frontLock = PTHREAD_MUTEX_INITIALIZER;
static uint32_t frontSeq;
static uint32_t copiedSeq;

static int16_t audioScratch[2048 * 2];
static double lastRatio;

// --- logging ---------------------------------------------------------------

static void logCallback(struct mLogger* logger, int category, enum mLogLevel level, const char* format, va_list args) {
    (void) logger;
    if (!(level & (mLOG_FATAL | mLOG_ERROR | mLOG_WARN))) {
        return;
    }
    char buf[512];
    vsnprintf(buf, sizeof(buf), format, args);
    int prio = (level & (mLOG_FATAL | mLOG_ERROR)) ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN;
    __android_log_print(prio, "mGBA", "[%s] %s", mLogCategoryName(category), buf);
}

static struct mLogger logger = { .log = logCallback, .filter = NULL };

// --- helpers ---------------------------------------------------------------

static void setResampleRatio(double ratio) {
    if (ratio == lastRatio) {
        return;
    }
    lastRatio = ratio;
    double out = audio_sample_rate() * ratio;
    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), out);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), out);
}

// Moves every sample the core produced this frame out of the blip buffers,
// optionally forwarding it to the audio device.
static void drainAudio(bool output) {
    blip_t* left = core->getAudioChannel(core, 0);
    blip_t* right = core->getAudioChannel(core, 1);
    int avail = blip_samples_avail(left);
    while (avail > 0) {
        int n = avail < 2048 ? avail : 2048;
        int got = blip_read_samples(left, audioScratch, n, true);
        blip_read_samples(right, audioScratch + 1, n, true);
        if (got <= 0) {
            break;
        }
        if (output) {
            audio_push(audioScratch, (size_t) got);
        }
        avail -= got;
    }
}

static void publishFrame(void) {
    pthread_mutex_lock(&frontLock);
    // Force opaque alpha so the buffer can be used directly as an ARGB_8888
    // bitmap (RGBA byte order on little-endian) and a GL_RGBA texture.
    for (int i = 0; i < PIXELS; ++i) {
        frontBuffer[i] = (uint32_t) backBuffer[i] | 0xFF000000u;
    }
    ++frontSeq;
    pthread_mutex_unlock(&frontLock);
}

static bool writeFileAtomic(const char* path, const void* data, size_t size) {
    char tmp[PATH_MAX];
    snprintf(tmp, sizeof(tmp), "%s.tmp", path);
    int fd = open(tmp, O_CREAT | O_TRUNC | O_WRONLY, 0644);
    if (fd < 0) {
        LOGE("open %s: %s", tmp, strerror(errno));
        return false;
    }
    const uint8_t* p = data;
    size_t left = size;
    while (left) {
        ssize_t w = write(fd, p, left);
        if (w < 0) {
            if (errno == EINTR) continue;
            LOGE("write %s: %s", tmp, strerror(errno));
            close(fd);
            unlink(tmp);
            return false;
        }
        p += w;
        left -= (size_t) w;
    }
    fsync(fd);
    close(fd);
    if (rename(tmp, path) != 0) {
        LOGE("rename %s: %s", path, strerror(errno));
        unlink(tmp);
        return false;
    }
    return true;
}

static void flushSavedata(void) {
    if (!core || !romLoaded) {
        return;
    }
    struct GBA* gba = core->board;
    struct GBASavedata* sd = &gba->memory.savedata;
    if (sd->vf && sd->data && (sd->mapMode & MAP_WRITE)) {
        sd->vf->sync(sd->vf, sd->data, GBASavedataSize(sd));
    }
}

static void unloadRom(void) {
    if (!core) {
        return;
    }
    if (romLoaded) {
        flushSavedata();
        core->unloadROM(core);
        romLoaded = false;
    }
    mCoreConfigDeinit(&core->config);
    core->deinit(core);
    core = NULL;
}

// --- JNI: lifecycle ----------------------------------------------------------

JNIEXPORT jboolean JNICALL JNI(nativeLoadRom)(JNIEnv* env, jclass clazz, jbyteArray rom, jstring savePath, jstring biosPath, jboolean skipBios) {
    (void) clazz;
    unloadRom();
    mLogSetDefaultLogger(&logger);

    core = GBACoreCreate();
    if (!core || !core->init(core)) {
        LOGE("Failed to create core");
        core = NULL;
        return JNI_FALSE;
    }
    mCoreInitConfig(core, NULL);
    core->opts.skipBios = skipBios;
    core->opts.useBios = biosPath != NULL;
    // GBACoreCreate zeroes opts, and loading the config copies opts.volume into
    // the core's master volume, so it must be set or the game is silent.
    // Output volume is applied separately in audio.c.
    core->opts.volume = GBA_AUDIO_VOLUME_MAX;
    mCoreLoadConfig(core);

    core->setVideoBuffer(core, backBuffer, WIDTH);
    core->setAudioBufferSize(core, 2048);
    lastRatio = 0;
    setResampleRatio(1.0);

    jsize len = (*env)->GetArrayLength(env, rom);
    jbyte* bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    struct VFile* romVf = VFileMemChunk(bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    if (!romVf || !core->isROM(romVf) || !core->loadROM(core, romVf)) {
        LOGE("Not a valid GBA ROM");
        if (romVf) romVf->close(romVf);
        unloadRom();
        return JNI_FALSE;
    }
    romLoaded = true;

    const char* sp = (*env)->GetStringUTFChars(env, savePath, NULL);
    struct VFile* saveVf = VFileOpen(sp, O_CREAT | O_RDWR);
    if (!saveVf || !core->loadSave(core, saveVf)) {
        LOGW("Could not open save file %s", sp);
    }
    (*env)->ReleaseStringUTFChars(env, savePath, sp);

    if (biosPath) {
        const char* bp = (*env)->GetStringUTFChars(env, biosPath, NULL);
        struct VFile* biosVf = VFileOpen(bp, O_RDONLY);
        if (!biosVf || !core->loadBIOS(core, biosVf, 0)) {
            LOGW("BIOS %s rejected; using built-in HLE BIOS", bp);
            if (biosVf) biosVf->close(biosVf);
        }
        (*env)->ReleaseStringUTFChars(env, biosPath, bp);
    }

    core->reset(core);
    memset(backBuffer, 0, sizeof(backBuffer));
    publishFrame();
    return JNI_TRUE;
}

JNIEXPORT void JNICALL JNI(nativeUnload)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    unloadRom();
}

JNIEXPORT void JNICALL JNI(nativeReset)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (core && romLoaded) {
        core->reset(core);
    }
}

JNIEXPORT jstring JNICALL JNI(nativeGameTitle)(JNIEnv* env, jclass clazz) {
    (void) clazz;
    char title[17] = { 0 };
    if (core && romLoaded) {
        core->getGameTitle(core, title);
    }
    return (*env)->NewStringUTF(env, title);
}

JNIEXPORT jstring JNICALL JNI(nativeGameCode)(JNIEnv* env, jclass clazz) {
    (void) clazz;
    char code[16] = { 0 };
    if (core && romLoaded) {
        core->getGameCode(core, code);
    }
    return (*env)->NewStringUTF(env, code);
}

// --- JNI: running ------------------------------------------------------------

// Emulates `count` frames with the given key state. Only the last frame is
// published to the display. Audio is forwarded only when `audio` is true;
// otherwise it is generated and discarded (fast-forward / muted).
JNIEXPORT void JNICALL JNI(nativeRunFrames)(JNIEnv* env, jclass clazz, jint count, jint keys, jboolean audio) {
    (void) env;
    (void) clazz;
    if (!core || !romLoaded) {
        return;
    }
    audio_service();
    core->setKeys(core, (uint32_t) keys);
    for (jint i = 0; i < count; ++i) {
        core->runFrame(core);
        drainAudio(audio && count == 1);
    }
    if (audio && count == 1) {
        double fill = (double) audio_fill();
        double target = (double) audio_target_fill();
        double delta = DRC_MAX_DELTA * (target - fill) / target;
        if (delta > DRC_MAX_DELTA) delta = DRC_MAX_DELTA;
        if (delta < -DRC_MAX_DELTA) delta = -DRC_MAX_DELTA;
        // Quantize so blip_set_rates is not called every single frame.
        setResampleRatio(1.0 + (double) (int) (delta * 20000.0) / 20000.0);
    }
    publishFrame();
}

// Copies the newest frame into `dst` (direct ByteBuffer, 240*160*4 bytes).
// Returns false if no frame has been produced since the previous copy.
JNIEXPORT jboolean JNICALL JNI(nativeCopyFrame)(JNIEnv* env, jclass clazz, jobject dst, jboolean force) {
    (void) clazz;
    void* out = (*env)->GetDirectBufferAddress(env, dst);
    if (!out || (*env)->GetDirectBufferCapacity(env, dst) < (jlong) sizeof(frontBuffer)) {
        return JNI_FALSE;
    }
    pthread_mutex_lock(&frontLock);
    bool fresh = frontSeq != copiedSeq;
    if (fresh || force) {
        memcpy(out, frontBuffer, sizeof(frontBuffer));
        copiedSeq = frontSeq;
    }
    pthread_mutex_unlock(&frontLock);
    return fresh || force;
}

// --- JNI: saves ----------------------------------------------------------------

JNIEXPORT void JNICALL JNI(nativeFlushSave)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    flushSavedata();
}

// Replaces the cartridge save with the given raw .sav contents.
JNIEXPORT jboolean JNICALL JNI(nativeImportSave)(JNIEnv* env, jclass clazz, jbyteArray data) {
    (void) clazz;
    if (!core || !romLoaded) {
        return JNI_FALSE;
    }
    jsize len = (*env)->GetArrayLength(env, data);
    jbyte* bytes = (*env)->GetByteArrayElements(env, data, NULL);
    bool ok = core->savedataRestore(core, bytes, (size_t) len, true);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    flushSavedata();
    return ok;
}

JNIEXPORT jboolean JNICALL JNI(nativeSaveState)(JNIEnv* env, jclass clazz, jstring path) {
    (void) clazz;
    if (!core || !romLoaded) {
        return JNI_FALSE;
    }
    struct VFile* mem = VFileMemChunk(NULL, 0);
    bool ok = mCoreSaveStateNamed(core, mem, SAVESTATE_SAVEDATA | SAVESTATE_RTC | SAVESTATE_METADATA);
    if (ok) {
        size_t size = (size_t) mem->size(mem);
        void* data = mem->map(mem, size, MAP_READ);
        const char* p = (*env)->GetStringUTFChars(env, path, NULL);
        ok = data && writeFileAtomic(p, data, size);
        (*env)->ReleaseStringUTFChars(env, path, p);
        if (data) mem->unmap(mem, data, size);
    }
    mem->close(mem);
    return ok;
}

// Loads a save state. The cartridge save is deliberately NOT restored from the
// state (no SAVESTATE_SAVEDATA flag) so in-game saves and save states stay
// independent. On failure the previous emulator state is restored.
JNIEXPORT jboolean JNICALL JNI(nativeLoadState)(JNIEnv* env, jclass clazz, jstring path) {
    (void) clazz;
    if (!core || !romLoaded) {
        return JNI_FALSE;
    }
    const char* p = (*env)->GetStringUTFChars(env, path, NULL);
    struct VFile* vf = VFileOpen(p, O_RDONLY);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (!vf) {
        return JNI_FALSE;
    }
    struct VFile* backup = VFileMemChunk(NULL, 0);
    mCoreSaveStateNamed(core, backup, SAVESTATE_RTC);
    bool ok = mCoreLoadStateNamed(core, vf, SAVESTATE_RTC);
    vf->close(vf);
    if (!ok) {
        LOGW("Save state rejected; restoring previous state");
        backup->seek(backup, 0, SEEK_SET);
        mCoreLoadStateNamed(core, backup, SAVESTATE_RTC);
    }
    backup->close(backup);
    if (ok) {
        publishFrame();
    }
    return ok;
}

// --- JNI: audio ------------------------------------------------------------

JNIEXPORT jboolean JNICALL JNI(nativeAudioOpen)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    bool ok = audio_open();
    if (ok && core) {
        lastRatio = 0;
        setResampleRatio(1.0);
    }
    return ok;
}

JNIEXPORT void JNICALL JNI(nativeAudioClose)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    audio_close();
}

JNIEXPORT void JNICALL JNI(nativeAudioPause)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    audio_pause();
}

JNIEXPORT void JNICALL JNI(nativeAudioResume)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    audio_resume();
}

JNIEXPORT void JNICALL JNI(nativeAudioFlush)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    audio_flush();
}

JNIEXPORT void JNICALL JNI(nativeSetVolume)(JNIEnv* env, jclass clazz, jfloat volume) {
    (void) env;
    (void) clazz;
    audio_set_volume(volume);
}

// [sampleRate, queuedFrames, targetFrames, underruns]
JNIEXPORT void JNICALL JNI(nativeAudioStats)(JNIEnv* env, jclass clazz, jintArray out) {
    (void) clazz;
    jint v[4] = { audio_sample_rate(), (jint) audio_fill(), (jint) audio_target_fill(), (jint) audio_underruns() };
    (*env)->SetIntArrayRegion(env, out, 0, 4, v);
}
