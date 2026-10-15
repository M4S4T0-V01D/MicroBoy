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
#include <mgba/core/rewind.h>
#include <mgba/core/serialize.h>
#include <mgba/gba/core.h>
#include <mgba/internal/gba/gba.h>
#include <mgba-util/crc32.h>
#include <mgba-util/vfs.h>

#include <android/log.h>
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "audio.h"
#include "link.h"

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
// Output samples per emulated sample beyond the device rate: 1 / game speed.
// Slow motion stretches the audio instead of starving the device.
static double audioStretch = 1.0;

// Rewind history: a snapshot every `rewindInterval` emulated frames, kept as
// diffs by mGBA's rewind context.
static struct mCoreRewindContext rewindCtx;
static bool rewindOn;
static int rewindInterval = 2;
static int rewindCountdown;
static size_t rewindEntries;

// --- cartridge peripherals: tilt, gyro, solar sensor, rumble -----------------
// The UI thread writes sensor readings; the core reads them on the emulation
// thread. Values are already in mGBA's units (see nativeSetMotion).

static _Atomic int32_t tiltX, tiltY, gyroZ;
static _Atomic int sunLevel;           // 0 = dark .. 255 = bright sunlight
static _Atomic bool rumbleSeen;        // rumble was on at some point since last poll

static int32_t readTiltX(struct mRotationSource* r) { (void) r; return atomic_load(&tiltX); }
static int32_t readTiltY(struct mRotationSource* r) { (void) r; return atomic_load(&tiltY); }
static int32_t readGyroZ(struct mRotationSource* r) { (void) r; return atomic_load(&gyroZ); }
static void sampleLux(struct GBALuminanceSource* l) { (void) l; }
// The Boktai sensor counts up to this value, so less means brighter.
static uint8_t readLux(struct GBALuminanceSource* l) { (void) l; return (uint8_t) (0xFF - atomic_load(&sunLevel)); }
static void setRumble(struct mRumble* r, int enable) {
    (void) r;
    if (enable) atomic_store(&rumbleSeen, true);
}

static struct mRotationSource rotation = { .readTiltX = readTiltX, .readTiltY = readTiltY, .readGyroZ = readGyroZ };
static struct GBALuminanceSource luminance = { .sample = sampleLux, .readLuminance = readLux };
static struct mRumble rumble = { .setRumble = setRumble };

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
    double out = audio_sample_rate() * ratio * audioStretch;
    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), out);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), out);
}

// Dynamic rate control: nudges the resampling ratio so the device buffer
// stays near its target fill.
static void adjustAudioRate(void) {
    double fill = (double) audio_fill();
    double target = (double) audio_target_fill();
    double delta = DRC_MAX_DELTA * (target - fill) / target;
    if (delta > DRC_MAX_DELTA) delta = DRC_MAX_DELTA;
    if (delta < -DRC_MAX_DELTA) delta = -DRC_MAX_DELTA;
    // Quantize so blip_set_rates is not called every single frame.
    setResampleRatio(1.0 + (double) (int) (delta * 20000.0) / 20000.0);
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

// --- online link: a second core for the other player's console --------------
static struct mCore* peerCore;
static struct MBLinkCable cable;
static bool linked;
static color_t peerBuffer[PIXELS];

static void linkTeardown(void) {
    if (!linked) {
        return;
    }
    mbLinkDetach(&cable);
    mCoreConfigDeinit(&peerCore->config);
    peerCore->deinit(peerCore);
    peerCore = NULL;
    linked = false;
}

static void rewindStop(void) {
    if (rewindOn) {
        mCoreRewindContextDeinit(&rewindCtx);
        rewindOn = false;
    }
}

// Forgets the rewind history, so rewinding can't cross a reset or a loaded
// save state (e.g. back to the boot screen right after resuming).
static void rewindClear(void) {
    if (rewindOn) {
        mCoreRewindContextDeinit(&rewindCtx);
        mCoreRewindContextInit(&rewindCtx, rewindEntries, false);
        rewindCountdown = rewindInterval;
    }
}

static void unloadRom(void) {
    if (!core) {
        return;
    }
    rewindStop();
    linkTeardown();
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

// Creates a GBA core with MicroBoy's standard configuration and loads `rom`.
// Returns NULL if it isn't a GBA ROM.
static struct mCore* createCore(JNIEnv* env, jbyteArray rom, bool skipBios, bool useBios, color_t* video) {
    struct mCore* c = GBACoreCreate();
    if (!c || !c->init(c)) {
        LOGE("Failed to create core");
        return NULL;
    }
    mCoreInitConfig(c, NULL);
    c->opts.skipBios = skipBios;
    c->opts.useBios = useBios;
    // GBACoreCreate zeroes opts, and loading the config copies opts.volume into
    // the core's master volume, so it must be set or the game is silent.
    // Output volume is applied separately in audio.c.
    c->opts.volume = GBA_AUDIO_VOLUME_MAX;
    mCoreLoadConfig(c);
    c->setVideoBuffer(c, video, WIDTH);
    c->setAudioBufferSize(c, 2048);

    jsize len = (*env)->GetArrayLength(env, rom);
    jbyte* bytes = (*env)->GetByteArrayElements(env, rom, NULL);
    struct VFile* romVf = VFileMemChunk(bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, rom, bytes, JNI_ABORT);
    if (!romVf || !c->isROM(romVf) || !c->loadROM(c, romVf)) {
        LOGE("Not a valid GBA ROM");
        if (romVf) romVf->close(romVf);
        mCoreConfigDeinit(&c->config);
        c->deinit(c);
        return NULL;
    }
    return c;
}

static void attachSensors(struct mCore* c, bool on) {
    c->setPeripheral(c, mPERIPH_ROTATION, on ? &rotation : NULL);
    c->setPeripheral(c, mPERIPH_GBA_LUMINANCE, on ? &luminance : NULL);
}

JNIEXPORT jboolean JNICALL JNI(nativeLoadRom)(JNIEnv* env, jclass clazz, jbyteArray rom, jstring savePath, jstring biosPath, jboolean skipBios) {
    (void) clazz;
    unloadRom();
    mLogSetDefaultLogger(&logger);

    core = createCore(env, rom, skipBios, biosPath != NULL, backBuffer);
    if (!core) {
        return JNI_FALSE;
    }
    attachSensors(core, true);
    core->setPeripheral(core, mPERIPH_RUMBLE, &rumble);
    atomic_store(&rumbleSeen, false);
    lastRatio = 0;
    setResampleRatio(1.0);
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
        rewindClear();
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
    if (!core || !romLoaded || linked) {
        return;
    }
    audio_service();
    core->setKeys(core, (uint32_t) keys);
    for (jint i = 0; i < count; ++i) {
        core->runFrame(core);
        drainAudio(audio && count == 1);
        if (rewindOn && --rewindCountdown <= 0) {
            mCoreRewindAppend(&rewindCtx, core);
            rewindCountdown = rewindInterval;
        }
    }
    if (audio && count == 1) {
        adjustAudioRate();
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
        rewindClear();
        publishFrame();
    }
    return ok;
}

// --- JNI: online link -------------------------------------------------------------
// Both phones run both consoles. Each console starts from its owner's
// snapshot, and afterwards only button presses are exchanged, so everything
// that affects emulation must be identical on both phones: HLE BIOS, a fake
// clock derived from the agreed epoch, and no sensors.

static jbyteArray stateBytes(JNIEnv* env, struct mCore* c, int flags) {
    struct VFile* vf = VFileMemChunk(NULL, 0);
    mCoreSaveStateNamed(c, vf, flags);
    size_t size = (size_t) vf->size(vf);
    void* data = vf->map(vf, size, MAP_READ);
    jbyteArray out = (*env)->NewByteArray(env, (jsize) size);
    (*env)->SetByteArrayRegion(env, out, 0, (jsize) size, data);
    vf->unmap(vf, data, size);
    vf->close(vf);
    return out;
}

static bool loadStateBytes(JNIEnv* env, struct mCore* c, jbyteArray state) {
    jsize len = (*env)->GetArrayLength(env, state);
    jbyte* bytes = (*env)->GetByteArrayElements(env, state, NULL);
    struct VFile* vf = VFileMemChunk(bytes, (size_t) len);
    (*env)->ReleaseByteArrayElements(env, state, bytes, JNI_ABORT);
    bool ok = mCoreLoadStateNamed(c, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
    vf->close(vf);
    return ok;
}

// This console's state, including its cartridge save, to send to the peer.
JNIEXPORT jbyteArray JNICALL JNI(nativeLinkSnapshot)(JNIEnv* env, jclass clazz) {
    (void) clazz;
    if (!core || !romLoaded) {
        return NULL;
    }
    return stateBytes(env, core, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
}

// Rebuilds both consoles from the two snapshots and connects the cable.
// The local console keeps writing its real save file; the peer's lives in memory.
JNIEXPORT jboolean JNICALL JNI(nativeLinkBegin)(JNIEnv* env, jclass clazz, jbyteArray localRom, jbyteArray localState,
                                                jbyteArray peerRom, jbyteArray peerState, jint localPlayer,
                                                jlong epochMs, jstring savePath) {
    (void) clazz;
    unloadRom();
    core = createCore(env, localRom, true, false, backBuffer);
    peerCore = core ? createCore(env, peerRom, true, false, peerBuffer) : NULL;
    if (!core || !peerCore) {
        if (core) { mCoreConfigDeinit(&core->config); core->deinit(core); core = NULL; }
        return JNI_FALSE;
    }
    const char* sp = (*env)->GetStringUTFChars(env, savePath, NULL);
    core->loadSave(core, VFileOpen(sp, O_CREAT | O_RDWR));
    (*env)->ReleaseStringUTFChars(env, savePath, sp);
    peerCore->loadSave(peerCore, VFileMemChunk(NULL, 0));
    core->setPeripheral(core, mPERIPH_RUMBLE, &rumble);  // output only, safe
    core->reset(core);
    peerCore->reset(peerCore);
    romLoaded = true;
    bool localOk = loadStateBytes(env, core, localState);
    if (!localOk || !loadStateBytes(env, peerCore, peerState)) {
        // Carry on alone from this console's snapshot.
        LOGE("Link: %s snapshot was rejected", localOk ? "the peer's" : "our");
        mCoreConfigDeinit(&peerCore->config);
        peerCore->deinit(peerCore);
        peerCore = NULL;
        attachSensors(core, true);
        publishFrame();
        return JNI_FALSE;
    }
    struct mCore* both[2] = { core, peerCore };
    for (int i = 0; i < 2; ++i) {
        both[i]->rtc.override = RTC_FAKE_EPOCH;
        both[i]->rtc.value = epochMs;
    }
    mbLinkAttach(&cable, localPlayer == 0 ? core : peerCore, localPlayer == 0 ? peerCore : core);
    linked = true;
    lastRatio = 0;
    setResampleRatio(1.0);
    publishFrame();
    return JNI_TRUE;
}

// Runs one linked frame with both players' buttons.
JNIEXPORT void JNICALL JNI(nativeLinkRunTick)(JNIEnv* env, jclass clazz, jint keysP1, jint keysP2, jboolean audio) {
    (void) env;
    (void) clazz;
    if (!linked) {
        return;
    }
    audio_service();
    cable.cores[0]->setKeys(cable.cores[0], (uint32_t) keysP1);
    cable.cores[1]->setKeys(cable.cores[1], (uint32_t) keysP2);
    mbLinkRunTick(&cable);
    drainAudio(audio);
    blip_clear(peerCore->getAudioChannel(peerCore, 0));
    blip_clear(peerCore->getAudioChannel(peerCore, 1));
    if (audio) {
        adjustAudioRate();
    }
    publishFrame();
}

// Checksum of both consoles, player 1 first, for detecting desyncs.
JNIEXPORT jint JNICALL JNI(nativeLinkChecksum)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (!linked) {
        return 0;
    }
    uint32_t crc = 0;
    for (int i = 0; i < 2; ++i) {
        struct VFile* vf = VFileMemChunk(NULL, 0);
        mCoreSaveStateNamed(cable.cores[i], vf, 0);
        size_t size = (size_t) vf->size(vf);
        void* data = vf->map(vf, size, MAP_READ);
        crc = crc * 31 + doCrc32(data, size);
        vf->unmap(vf, data, size);
        vf->close(vf);
    }
    return (jint) crc;
}

// Unplugs the cable and drops the peer's console; this console keeps
// running from exactly where the link left it, with the real clock again.
JNIEXPORT void JNICALL JNI(nativeLinkEnd)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (!linked) {
        return;
    }
    linkTeardown();
    core->rtc.override = RTC_NO_OVERRIDE;
    attachSensors(core, true);
    flushSavedata();
}

JNIEXPORT jboolean JNICALL JNI(nativeLinkActive)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    return linked;
}

// --- JNI: peripherals ----------------------------------------------------------

// Bitmask of the cartridge's extra hardware (mGBA's HW_* flags: 1 RTC,
// 2 rumble, 4 light sensor, 8 gyro, 16 tilt), known once the ROM is loaded.
JNIEXPORT jint JNICALL JNI(nativeCartHardware)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (!core || !romLoaded) {
        return 0;
    }
    struct GBA* gba = core->board;
    return (jint) gba->memory.hw.devices;
}

// Safe from any thread. Tilt is gravity in m/s² along the screen's x (right)
// and y (up) axes; gyro is rotation in rad/s around the axis out of the
// screen. The scale factors match mGBA's libretro port.
JNIEXPORT void JNICALL JNI(nativeSetMotion)(JNIEnv* env, jclass clazz, jfloat tx, jfloat ty, jfloat gz) {
    (void) env;
    (void) clazz;
    atomic_store(&tiltX, (int32_t) (tx * -2e8f));
    atomic_store(&tiltY, (int32_t) (ty * 2e8f));
    atomic_store(&gyroZ, (int32_t) (gz * -5.5e8f));
}

// Safe from any thread. 0 = dark .. 255 = full sun.
JNIEXPORT void JNICALL JNI(nativeSetSun)(JNIEnv* env, jclass clazz, jint level) {
    (void) env;
    (void) clazz;
    atomic_store(&sunLevel, level < 0 ? 0 : level > 255 ? 255 : level);
}

// Returns whether the cartridge turned rumble on since the previous call.
// Games pulse the motor, so this is polled once per frame rather than mirrored.
JNIEXPORT jboolean JNICALL JNI(nativePollRumble)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    return atomic_exchange(&rumbleSeen, false);
}

// --- JNI: rewind and speed --------------------------------------------------

// (Re)configures rewind. Changing the size or interval discards the history.
JNIEXPORT void JNICALL JNI(nativeSetRewind)(JNIEnv* env, jclass clazz, jboolean enabled, jint entries, jint interval) {
    (void) env;
    (void) clazz;
    rewindStop();
    if (!enabled || entries <= 0 || !core || !romLoaded) {
        return;
    }
    rewindEntries = (size_t) entries;
    mCoreRewindContextInit(&rewindCtx, rewindEntries, false);
    rewindInterval = interval > 0 ? interval : 1;
    rewindCountdown = rewindInterval;
    rewindOn = true;
}

// Steps one snapshot back and shows it. Returns false when the history is
// used up. Like loading a save state, rewinding never undoes the in-game save:
// mGBA's snapshots include the cartridge save, so the current one is put back.
JNIEXPORT jboolean JNICALL JNI(nativeRewindStep)(JNIEnv* env, jclass clazz) {
    (void) env;
    (void) clazz;
    if (!core || !romLoaded || !rewindOn) {
        return JNI_FALSE;
    }
    void* sram = NULL;
    size_t size = core->savedataClone(core, &sram);
    bool ok = mCoreRewindRestore(&rewindCtx, core);
    if (ok) {
        if (size) {
            core->savedataRestore(core, sram, size, true);
        }
        // A restored state has no picture yet; one frame renders it.
        core->runFrame(core);
        drainAudio(false);
        rewindCountdown = rewindInterval;
        publishFrame();
    }
    free(sram);
    return ok;
}

// Game speed for slow motion (1 = normal). Audio is resampled to match.
JNIEXPORT void JNICALL JNI(nativeSetSpeed)(JNIEnv* env, jclass clazz, jfloat speed) {
    (void) env;
    (void) clazz;
    audioStretch = speed > 0 ? 1.0 / speed : 1.0;
    if (core) {
        lastRatio = 0;
        setResampleRatio(1.0);
    }
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
