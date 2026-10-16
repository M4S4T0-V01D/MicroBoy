// The scene record that gen.py fills in for each Jukebox scene.
#pragma once
#include <stdint.h>

typedef struct {
    const uint32_t* tiles;
    uint16_t tileWords;
    const uint16_t* map[4];   // front to back; NULL = layer unused
    uint8_t blocks[4];        // 1 = 256 px wide, 2 = 512 px wide
    int16_t scroll[4];        // 1/256 px per frame
    const uint16_t* pal;      // BG banks 0-3, OBJ bank 1 (particles), OBJ bank 2 (mascot)
    const uint16_t* flash;    // lightning palette for the flash effect, or NULL
    uint8_t fx[3][5];         // type (1 cycle, 2 twinkle, 3 flash), bank, first slot, count, frames
    const uint32_t* ptiles;
    uint8_t pframes, pcount, panim, psway;
    int16_t pvx, pvy;
    const uint32_t* mtiles;
    uint8_t mframes, msize, mmotion, manim, mamp;
    int16_t mx, my, mvx;
    uint8_t mcount;           // how many copies march in line (1 = single mascot)
    uint8_t mkinds;           // bit k set: member k uses frames 2-3 instead of 0-1
    int16_t moffs[8];         // member k is drawn this many pixels behind the leader
    const uint16_t* lead;
    const uint16_t* harm;
    const uint16_t* bass;
    const uint8_t* drums;
    const uint8_t* wave;
    uint8_t tempo, swing, leadDuty, leadVol, leadStep, harmDuty, harmVol, harmStep, bassVol;
    const char* name;
} Scene;
