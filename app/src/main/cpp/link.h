// MicroBoy link cable: two mGBA cores connected by a deterministic, single
// threaded serial link.
//
// Online play runs *both* consoles on *both* phones and only exchanges button
// presses, so the link has to give bit-identical results everywhere. Instead
// of mGBA's thread-based lockstep, the cable advances the two cores in fixed
// slices of emulated time, always player 0 first, and latches transfer data
// at points that depend only on emulated time.
#pragma once

#include <mgba/core/core.h>
#include <mgba/core/timing.h>
#include <mgba/internal/gba/sio.h>

#include <stdbool.h>
#include <stdint.h>

#define MB_LINK_SLICE 1024  // cycles each core runs before the other catches up

struct MBLinkCable;

struct MBLinkPort {
    struct GBASIODriver d;  // must be first
    struct MBLinkCable* cable;
    int id;                 // 0 = player 1 (multiplayer master), 1 = player 2
    int mode;               // active enum GBASIOMode, or -1 while unloaded
    struct mTimingEvent sliceEvent;
    struct mTimingEvent latchEvent;
    struct mTimingEvent finishEvent;
    bool transferActive;    // this port takes part in the current transfer
};

struct MBLinkCable {
    struct mCore* cores[2];
    struct MBLinkPort ports[2];
    uint32_t mark[2];       // each core's 32-bit current time at the last sync
    uint64_t elapsed[2];    // cycles each core has run since attaching
    uint64_t sliceEnd;      // link time the current slice runs to
    uint64_t ticks;         // ticks run since attaching
    // Current transfer
    bool busy;
    int initiator;
    int transferMode;
    uint64_t finishAt;
    uint16_t multiRecv[4];
    uint32_t normalRecv[2];
};

// Connects the cores' serial ports. Both must be GBA cores with a ROM loaded.
void mbLinkAttach(struct MBLinkCable*, struct mCore* player1, struct mCore* player2);
void mbLinkDetach(struct MBLinkCable*);

// True when no transfer is in flight, i.e. a safe moment to snapshot.
bool mbLinkIdle(const struct MBLinkCable*);

// Runs both consoles for one frame period of emulated time (280896 cycles).
// Each core's runFrame-equivalent output (video, audio) accumulates as usual.
void mbLinkRunTick(struct MBLinkCable*);
