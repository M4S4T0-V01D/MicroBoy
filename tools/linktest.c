// linktest: checks MicroBoy's link cable (app/src/main/cpp/link.c) on the host.
//
//   linktest ROM [ROM2]
//
// 1. Boots two consoles linked together and reports what testroms/link-test
//    recorded (transfers, echo and sequence errors).
// 2. Snapshots both, then runs two independent copies from that snapshot,
//    the way two phones do online, and checks that they stay bit-identical.
// 3. Reports the cost of a linked frame.
//
// Build: tools/linktest.sh BUILD_DIR (reuses romshot's host libmgba).
#include "link.h"

#include <mgba/core/serialize.h>
#include <mgba/gba/core.h>
#include <mgba-util/crc32.h>
#include <mgba-util/vfs.h>

#include <stdio.h>
#include <stdlib.h>
#include <time.h>

static double now(void) {
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return t.tv_sec + t.tv_nsec / 1e9;
}

static color_t video[4][240 * 160];

static struct mCore* boot(const char* rom, int slot) {
    struct mCore* core = GBACoreCreate();
    core->init(core);
    mCoreInitConfig(core, NULL);
    mCoreLoadConfig(core);
    core->setVideoBuffer(core, video[slot], 240);
    if (!mCoreLoadFile(core, rom)) {
        fprintf(stderr, "cannot load %s\n", rom);
        exit(1);
    }
    core->loadSave(core, VFileMemChunk(NULL, 0));
    core->reset(core);
    return core;
}

static struct VFile* snapshot(struct mCore* core) {
    struct VFile* vf = VFileMemChunk(NULL, 0);
    mCoreSaveStateNamed(core, vf, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
    return vf;
}

static uint32_t stateCrc(struct mCore* core) {
    struct VFile* vf = VFileMemChunk(NULL, 0);
    mCoreSaveStateNamed(core, vf, 0);
    size_t size = vf->size(vf);
    void* data = vf->map(vf, size, MAP_READ);
    uint32_t crc = doCrc32(data, size);
    vf->unmap(vf, data, size);
    vf->close(vf);
    return crc;
}

static void stats(const char* label, struct mCore* core) {
    uint32_t s[6];
    for (int i = 0; i < 6; ++i) s[i] = core->rawRead32(core, 0x02000000 + 4 * i, -1);
    printf("  %s: id %u, %u transfers in %u frames, echo errors %u, sequence errors %u\n",
           label, s[4], s[0], s[3], s[1], s[2]);
}

static void runTicks(struct MBLinkCable* cable, int n, double* seconds) {
    double t = now();
    for (int i = 0; i < n; ++i) mbLinkRunTick(cable);
    if (seconds) *seconds = now() - t;
}

int main(int argc, char** argv) {
    if (argc < 2) {
        fprintf(stderr, "usage: %s ROM [ROM2]\n", argv[0]);
        return 2;
    }
    const char* romB = argc > 2 ? argv[2] : argv[1];

    printf("1. Fresh boot, linked, 600 frames\n");
    struct mCore* a = boot(argv[1], 0);
    struct mCore* b = boot(romB, 1);
    struct MBLinkCable cable;
    mbLinkAttach(&cable, a, b);
    double secs;
    runTicks(&cable, 600, &secs);
    stats("player 1", a);
    stats("player 2", b);
    printf("  %.3f ms per linked frame (both consoles)\n", secs * 1000 / 600);

    printf("2. Two phones starting from the same snapshot, 1200 frames\n");
    struct VFile* sa = snapshot(a);
    struct VFile* sb = snapshot(b);
    uint32_t crcs[2][2];
    for (int phone = 0; phone < 2; ++phone) {
        struct mCore* pa = boot(argv[1], 2);
        struct mCore* pb = boot(romB, 3);
        sa->seek(sa, 0, SEEK_SET);
        sb->seek(sb, 0, SEEK_SET);
        mCoreLoadStateNamed(pa, sa, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
        mCoreLoadStateNamed(pb, sb, SAVESTATE_SAVEDATA | SAVESTATE_RTC);
        struct MBLinkCable c;
        mbLinkAttach(&c, pa, pb);
        runTicks(&c, 1200, NULL);
        crcs[phone][0] = stateCrc(pa);
        crcs[phone][1] = stateCrc(pb);
        printf("  phone %c: console 1 %08X, console 2 %08X\n", 'A' + phone, crcs[phone][0], crcs[phone][1]);
        if (phone == 1) {
            stats("player 1", pa);
            stats("player 2", pb);
        }
        mbLinkDetach(&c);
        pa->deinit(pa);
        pb->deinit(pb);
    }
    int same = crcs[0][0] == crcs[1][0] && crcs[0][1] == crcs[1][1];
    printf("  %s\n", same ? "identical: PASS" : "DIVERGED: FAIL");
    return same ? 0 : 1;
}
