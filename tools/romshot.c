// romshot: runs a GBA ROM headless on mGBA and writes screenshots, so homebrew
// ROMs (testroms/) can be checked without a device.
//
//   romshot ROM OUTDIR EVENT...
//
// Each EVENT is FRAME (save OUTDIR/FRAME.ppm) or FRAME:KEYS (hold KEYS from
// that frame for 4 frames). KEYS uses a b s(elect) t(start) r l u d R L.
// Prints the audio level so silent output is obvious. With ROMSHOT_WAV=path it
// also writes the audio (48 kHz stereo) to a WAV file.
//
// Build against a host libmgba (see tools/romshot.sh).
#include <mgba/core/blip_buf.h>
#include <mgba/core/core.h>
#include <mgba/gba/core.h>
#include <mgba-util/vfs.h>

#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define W 240
#define H 160

static int keyMask(const char* s) {
    static const char names[] = "abstrludRL";
    int mask = 0;
    for (; *s; ++s) {
        const char* p = strchr(names, *s);
        if (p) mask |= 1 << (p - names);
    }
    return mask;
}

static void savePpm(const char* path, const color_t* px) {
    FILE* f = fopen(path, "wb");
    if (!f) { perror(path); return; }
    fprintf(f, "P6\n%d %d\n255\n", W, H);
    for (int i = 0; i < W * H; ++i) {
        uint32_t c = px[i];  // mGBA's 32-bit color_t is 0x00BBGGRR
        unsigned char rgb[3] = { c & 0xFF, (c >> 8) & 0xFF, (c >> 16) & 0xFF };
        fwrite(rgb, 1, 3, f);
    }
    fclose(f);
}

int main(int argc, char** argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s ROM OUTDIR EVENT...\n", argv[0]);
        return 2;
    }
    struct mCore* core = GBACoreCreate();
    core->init(core);
    mCoreInitConfig(core, NULL);
    core->opts.volume = 0x100;
    mCoreLoadConfig(core);
    static color_t buffer[W * H];
    core->setVideoBuffer(core, buffer, W);
    core->setAudioBufferSize(core, 4096);
    blip_set_rates(core->getAudioChannel(core, 0), core->frequency(core), 48000);
    blip_set_rates(core->getAudioChannel(core, 1), core->frequency(core), 48000);
    if (!mCoreLoadFile(core, argv[1])) {
        fprintf(stderr, "cannot load %s\n", argv[1]);
        return 1;
    }
    core->reset(core);

    int last = 0;
    for (int i = 3; i < argc; ++i) {
        int f = atoi(argv[i]);
        if (f > last) last = f;
    }

    double sumSq = 0;
    long samples = 0;
    int peak = 0;
    const char* wavPath = getenv("ROMSHOT_WAV");
    FILE* wav = wavPath ? fopen(wavPath, "wb") : NULL;
    if (wav) fseek(wav, 44, SEEK_SET);
    int held = 0, holdUntil = -1;
    static int16_t audio[4096 * 2];
    for (int frame = 0; frame <= last; ++frame) {
        for (int i = 3; i < argc; ++i) {
            char* colon = strchr(argv[i], ':');
            if (colon && atoi(argv[i]) == frame) {
                held = keyMask(colon + 1);
                holdUntil = frame + 4;
            }
        }
        if (frame >= holdUntil) held = 0;
        core->setKeys(core, (uint32_t) held);
        core->runFrame(core);

        blip_t* left = core->getAudioChannel(core, 0);
        blip_t* right = core->getAudioChannel(core, 1);
        int n;
        while ((n = blip_read_samples(left, audio, 4096, true)) > 0) {
            blip_read_samples(right, audio + 1, n, true);
            for (int s = 0; s < n; ++s) {
                sumSq += (double) audio[2 * s] * audio[2 * s];
                if (abs(audio[2 * s]) > peak) peak = abs(audio[2 * s]);
            }
            if (wav) fwrite(audio, 4, (size_t) n, wav);
            samples += n;
        }

        for (int i = 3; i < argc; ++i) {
            if (!strchr(argv[i], ':') && atoi(argv[i]) == frame) {
                char path[512];
                snprintf(path, sizeof(path), "%s/%d.ppm", argv[2], frame);
                savePpm(path, buffer);
            }
        }
    }
    printf("audio: rms %.0f, peak %d over %ld samples\n", samples ? sqrt(sumSq / samples) : 0.0, peak, samples);
    if (wav) {
        uint32_t data = (uint32_t) samples * 4, rate = 48000, byteRate = rate * 4, riff = 36 + data;
        uint16_t fmt[] = { 1, 2 }, align[] = { 4, 16 };
        uint32_t fmtLen = 16;
        fseek(wav, 0, SEEK_SET);
        fwrite("RIFF", 1, 4, wav); fwrite(&riff, 4, 1, wav); fwrite("WAVEfmt ", 1, 8, wav);
        fwrite(&fmtLen, 4, 1, wav); fwrite(fmt, 2, 2, wav); fwrite(&rate, 4, 1, wav);
        fwrite(&byteRate, 4, 1, wav); fwrite(align, 2, 2, wav); fwrite("data", 1, 4, wav); fwrite(&data, 4, 1, wav);
        fclose(wav);
    }
    core->deinit(core);
    return 0;
}
