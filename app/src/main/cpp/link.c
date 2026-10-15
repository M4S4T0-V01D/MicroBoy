// MicroBoy link cable. See link.h for why it isn't mGBA's lockstep.
//
// Timing model. Each core has a "link time": cycles since mbLinkAttach. A tick
// is split into slices of MB_LINK_SLICE cycles; in every slice player 1 runs
// to the slice end first, then player 2. A transfer started at link time t0:
//   - latches the initiator's data at t0;
//   - latches the partner's data at t0 if the partner hasn't reached t0 yet
//     (the usual case, since player 1 initiates multiplayer transfers),
//     otherwise right away;
//   - finishes on both consoles at max(t0 + transfer length, slice end + 1),
//     so both latches have happened before either side completes.
// Everything depends only on emulated time, so every phone gets the same
// result, and transfers are never stretched by more than one slice.
#include "link.h"

#include <mgba/internal/gba/gba.h>
#include <mgba/internal/gba/io.h>

#include <string.h>

#define TICK_CYCLES 280896

// mGBA 0.10's 64-bit global time isn't maintained, so link time is built
// from the 32-bit current time, which wraps every ~4 minutes.
static uint64_t linkTime(struct MBLinkCable* cable, int i) {
    struct GBA* gba = cable->cores[i]->board;
    uint32_t now = (uint32_t) mTimingCurrentTime(&gba->timing);
    return cable->elapsed[i] + (uint32_t) (now - cable->mark[i]);
}

static void markTime(struct MBLinkCable* cable, int i) {
    struct GBA* gba = cable->cores[i]->board;
    uint32_t now = (uint32_t) mTimingCurrentTime(&gba->timing);
    cable->elapsed[i] += (uint32_t) (now - cable->mark[i]);
    cable->mark[i] = now;
}

static struct GBA* gbaOf(struct MBLinkPort* port) {
    return port->d.p->p;
}

static void scheduleAt(struct MBLinkCable* cable, int i, struct mTimingEvent* event, uint64_t when) {
    struct GBA* gba = cable->cores[i]->board;
    uint64_t now = linkTime(cable, i);
    int32_t rel = when > now ? (int32_t) (when - now) : 0;
    if (mTimingIsScheduled(&gba->timing, event)) {
        mTimingDeschedule(&gba->timing, event);
    }
    mTimingSchedule(&gba->timing, event, rel);
}

// --- multiplayer bits -----------------------------------------------------------

static void updateReady(struct MBLinkCable* cable) {
    bool ready = cable->ports[0].mode == SIO_MULTI && cable->ports[1].mode == SIO_MULTI;
    for (int i = 0; i < 2; ++i) {
        struct MBLinkPort* port = &cable->ports[i];
        if (port->mode == SIO_MULTI) {
            port->d.p->siocnt = GBASIOMultiplayerSetReady(port->d.p->siocnt, ready);
        }
    }
}

// --- transfers ---------------------------------------------------------------------

static void latch(struct MBLinkPort* port) {
    struct MBLinkCable* cable = port->cable;
    struct GBA* gba = gbaOf(port);
    struct GBASIO* sio = port->d.p;
    if (port->mode != cable->transferMode) {
        port->transferActive = false;
        return;
    }
    switch (cable->transferMode) {
    case SIO_MULTI:
        cable->multiRecv[port->id] = gba->memory.io[REG_SIOMLT_SEND >> 1];
        if (port->id != cable->initiator) {
            sio->rcnt &= ~1;
            sio->siocnt = GBASIOMultiplayerFillBusy(sio->siocnt);
        }
        port->transferActive = true;
        break;
    case SIO_NORMAL_8:
    case SIO_NORMAL_32:
        // The partner only shifts if it armed its own transfer (Start set).
        if (port->id != cable->initiator && !GBASIONormalIsStart(sio->siocnt)) {
            port->transferActive = false;
            cable->normalRecv[port->id] = 0xFFFFFFFF;
            return;
        }
        if (cable->transferMode == SIO_NORMAL_8) {
            cable->normalRecv[port->id] = gba->memory.io[REG_SIODATA8 >> 1] & 0xFF;
        } else {
            cable->normalRecv[port->id] = gba->memory.io[REG_SIODATA32_LO >> 1] | ((uint32_t) gba->memory.io[REG_SIODATA32_HI >> 1] << 16);
        }
        port->transferActive = true;
        break;
    default:
        port->transferActive = false;
        break;
    }
}

static void finish(struct MBLinkPort* port) {
    struct MBLinkCable* cable = port->cable;
    struct GBA* gba = gbaOf(port);
    struct GBASIO* sio = port->d.p;
    if (!port->transferActive) {
        return;
    }
    port->transferActive = false;
    switch (cable->transferMode) {
    case SIO_MULTI:
        gba->memory.io[REG_SIOMULTI0 >> 1] = cable->multiRecv[0];
        gba->memory.io[REG_SIOMULTI1 >> 1] = cable->multiRecv[1];
        gba->memory.io[REG_SIOMULTI2 >> 1] = cable->multiRecv[2];
        gba->memory.io[REG_SIOMULTI3 >> 1] = cable->multiRecv[3];
        sio->rcnt |= 1;
        sio->siocnt = GBASIOMultiplayerClearBusy(sio->siocnt);
        sio->siocnt = GBASIOMultiplayerSetId(sio->siocnt, port->id);
        if (GBASIOMultiplayerIsIrq(sio->siocnt)) {
            GBARaiseIRQ(gba, GBA_IRQ_SIO, 0);
        }
        break;
    case SIO_NORMAL_8:
    case SIO_NORMAL_32: {
        uint32_t data = cable->normalRecv[1 - port->id];
        if (cable->transferMode == SIO_NORMAL_8) {
            gba->memory.io[REG_SIODATA8 >> 1] = data & 0xFF;
        } else {
            gba->memory.io[REG_SIODATA32_LO >> 1] = data;
            gba->memory.io[REG_SIODATA32_HI >> 1] = data >> 16;
        }
        sio->siocnt = GBASIONormalClearStart(sio->siocnt);
        if (GBASIONormalIsIrq(sio->siocnt)) {
            GBARaiseIRQ(gba, GBA_IRQ_SIO, 0);
        }
        break;
    }
    default:
        break;
    }
    if (!cable->ports[0].transferActive && !cable->ports[1].transferActive) {
        cable->busy = false;
    }
}

static void latchEvent(struct mTiming* timing, void* user, uint32_t late) {
    (void) timing;
    (void) late;
    latch(user);
}

static void finishEvent(struct mTiming* timing, void* user, uint32_t late) {
    (void) timing;
    (void) late;
    finish(user);
}

static void sliceEvent(struct mTiming* timing, void* user, uint32_t late) {
    (void) timing;
    (void) late;
    (void) user;  // only here to make runLoop return at the slice boundary
}

static void startTransfer(struct MBLinkPort* port, int mode, int32_t cycles) {
    struct MBLinkCable* cable = port->cable;
    int me = port->id;
    int other = 1 - me;
    uint64_t t0 = linkTime(cable, me);

    cable->busy = true;
    cable->initiator = me;
    cable->transferMode = mode;
    cable->multiRecv[2] = 0xFFFF;
    cable->multiRecv[3] = 0xFFFF;
    cable->multiRecv[other] = 0xFFFF;
    cable->normalRecv[other] = 0xFFFFFFFF;
    latch(port);

    struct MBLinkPort* partner = &cable->ports[other];
    partner->transferActive = false;
    if (linkTime(cable, other) <= t0) {
        scheduleAt(cable, other, &partner->latchEvent, t0);
    } else {
        latch(partner);
    }

    uint64_t at = t0 + (uint64_t) cycles;
    if (at <= cable->sliceEnd) {
        at = cable->sliceEnd + 1;
    }
    cable->finishAt = at;
    scheduleAt(cable, me, &port->finishEvent, at);
    scheduleAt(cable, other, &partner->finishEvent, at);
}

// --- driver callbacks ----------------------------------------------------------------

static uint16_t multiWrite(struct MBLinkPort* port, uint32_t address, uint16_t value) {
    struct MBLinkCable* cable = port->cable;
    struct GBASIO* sio = port->d.p;
    if (address != REG_SIOCNT) {
        return value;
    }
    sio->siocnt = GBASIOMultiplayerSetSlave(sio->siocnt, port->id != 0);
    bool start = (value & 0x0080) && !cable->busy && port->id == 0 &&
                 cable->ports[1].mode == SIO_MULTI && GBASIOMultiplayerIsReady(sio->siocnt);
    if (start) {
        // Store the new SIOCNT first so the latch sees this console's settings.
        sio->siocnt = (value & 0xFF83) | (sio->siocnt & 0x007C) | 0x0080;
        int baud = GBASIOMultiplayerGetBaud(sio->siocnt);
        startTransfer(port, SIO_MULTI, GBASIOCyclesPerTransfer[baud][1]);
        return sio->siocnt;
    }
    value &= 0xFF83;
    value |= sio->siocnt & 0x00FC;  // slave, ready, id, error and busy are read-only
    return value;
}

static uint16_t normalWrite(struct MBLinkPort* port, uint32_t address, uint16_t value) {
    struct MBLinkCable* cable = port->cable;
    struct GBASIO* sio = port->d.p;
    struct MBLinkPort* partner = &cable->ports[1 - port->id];
    if (address != REG_SIOCNT) {
        return value;
    }
    value &= 0xFF8B;
    // SI reads the partner's SO; tell the partner about ours.
    bool partnerSo = partner->mode == SIO_NORMAL_8 || partner->mode == SIO_NORMAL_32 ?
        GBASIONormalGetIdleSo(partner->d.p->siocnt) : true;
    value = GBASIONormalSetSi(value, partnerSo);
    if (partner->mode == SIO_NORMAL_8 || partner->mode == SIO_NORMAL_32) {
        partner->d.p->siocnt = GBASIONormalSetSi(partner->d.p->siocnt, GBASIONormalGetIdleSo(value));
    }
    if ((value & 0x0081) == 0x0081) {  // Start with the internal clock
        if (cable->busy) {
            return value & ~0x0080;
        }
        int32_t cycles = (value & 2) ? 8 * 8 : 64 * 8;
        if (value & 0x1000) {
            cycles *= 4;
        }
        sio->siocnt = value;
        startTransfer(port, sio->mode, cycles);
        return sio->siocnt;
    }
    return value;
}

static uint16_t writeRegister(struct GBASIODriver* driver, uint32_t address, uint16_t value) {
    struct MBLinkPort* port = (struct MBLinkPort*) driver;
    switch (port->mode) {
    case SIO_MULTI:
        return multiWrite(port, address, value);
    case SIO_NORMAL_8:
    case SIO_NORMAL_32:
        return normalWrite(port, address, value);
    default:
        return value;
    }
}

static bool portInit(struct GBASIODriver* driver) {
    (void) driver;
    return true;
}

static void portDeinit(struct GBASIODriver* driver) {
    (void) driver;
}

static bool portLoad(struct GBASIODriver* driver) {
    struct MBLinkPort* port = (struct MBLinkPort*) driver;
    struct GBASIO* sio = driver->p;
    port->mode = sio->mode;
    if (port->mode == SIO_MULTI) {
        if (port->id) {
            sio->rcnt |= 4;
            sio->siocnt = GBASIOMultiplayerFillSlave(sio->siocnt);
        } else {
            sio->rcnt &= ~4;
            sio->siocnt = GBASIOMultiplayerClearSlave(sio->siocnt);
        }
    }
    updateReady(port->cable);
    return true;
}

static bool portUnload(struct GBASIODriver* driver) {
    struct MBLinkPort* port = (struct MBLinkPort*) driver;
    port->mode = -1;
    updateReady(port->cable);
    return true;
}

// --- public ------------------------------------------------------------------------

void mbLinkAttach(struct MBLinkCable* cable, struct mCore* player1, struct mCore* player2) {
    memset(cable, 0, sizeof(*cable));
    cable->cores[0] = player1;
    cable->cores[1] = player2;
    for (int i = 0; i < 2; ++i) {
        struct MBLinkPort* port = &cable->ports[i];
        struct GBA* gba = cable->cores[i]->board;
        port->cable = cable;
        port->id = i;
        port->mode = -1;
        port->d.init = portInit;
        port->d.deinit = portDeinit;
        port->d.load = portLoad;
        port->d.unload = portUnload;
        port->d.writeRegister = writeRegister;
        port->sliceEvent = (struct mTimingEvent) { .context = port, .callback = sliceEvent, .name = "MicroBoy link slice", .priority = 0x80 };
        port->latchEvent = (struct mTimingEvent) { .context = port, .callback = latchEvent, .name = "MicroBoy link latch", .priority = 0x81 };
        port->finishEvent = (struct mTimingEvent) { .context = port, .callback = finishEvent, .name = "MicroBoy link finish", .priority = 0x82 };
        cable->mark[i] = (uint32_t) mTimingCurrentTime(&gba->timing);
        cable->elapsed[i] = 0;
    }
    for (int i = 0; i < 2; ++i) {
        struct GBA* gba = cable->cores[i]->board;
        // A transfer in flight when the state was saved can't complete: its
        // finish event isn't part of mGBA's save states. Cancel it, or the
        // console would stay busy forever.
        if (gba->sio.mode == SIO_MULTI) {
            gba->sio.siocnt = GBASIOMultiplayerClearBusy(gba->sio.siocnt);
        } else if (gba->sio.mode == SIO_NORMAL_8 || gba->sio.mode == SIO_NORMAL_32) {
            gba->sio.siocnt = GBASIONormalClearStart(gba->sio.siocnt);
        }
        GBASIOSetDriver(&gba->sio, &cable->ports[i].d, SIO_MULTI);
        GBASIOSetDriver(&gba->sio, &cable->ports[i].d, SIO_NORMAL_8);
    }
}

bool mbLinkIdle(const struct MBLinkCable* cable) {
    return !cable->busy;
}

void mbLinkDetach(struct MBLinkCable* cable) {
    for (int i = 0; i < 2; ++i) {
        struct MBLinkPort* port = &cable->ports[i];
        struct GBA* gba = cable->cores[i]->board;
        mTimingDeschedule(&gba->timing, &port->sliceEvent);
        mTimingDeschedule(&gba->timing, &port->latchEvent);
        mTimingDeschedule(&gba->timing, &port->finishEvent);
        GBASIOSetDriver(&gba->sio, NULL, SIO_MULTI);
        GBASIOSetDriver(&gba->sio, NULL, SIO_NORMAL_8);
    }
}

void mbLinkRunTick(struct MBLinkCable* cable) {
    uint64_t tickEnd = ++cable->ticks * (uint64_t) TICK_CYCLES;
    while (cable->sliceEnd < tickEnd) {
        cable->sliceEnd += MB_LINK_SLICE;
        if (cable->sliceEnd > tickEnd) {
            cable->sliceEnd = tickEnd;
        }
        for (int i = 0; i < 2; ++i) {
            struct mCore* core = cable->cores[i];
            struct MBLinkPort* port = &cable->ports[i];
            scheduleAt(cable, i, &port->sliceEvent, cable->sliceEnd);
            while (linkTime(cable, i) < cable->sliceEnd) {
                core->runLoop(core);
            }
            markTime(cable, i);
        }
    }
}
