// MicroBoy test ROM: visual input tester, frame counter, SRAM persistence and
// PSG audio. Written for this project; public domain / CC0.
#include <stdint.h>

#define REG16(a) (*(volatile uint16_t*) (a))
#define DISPCNT REG16(0x4000000)
#define VCOUNT REG16(0x4000006)
#define KEYINPUT REG16(0x4000130)
#define SOUND1CNT_L REG16(0x4000060)
#define SOUND1CNT_H REG16(0x4000062)
#define SOUND1CNT_X REG16(0x4000064)
#define SOUND2CNT_L REG16(0x4000068)
#define SOUND2CNT_H REG16(0x400006C)
#define SOUNDCNT_L REG16(0x4000080)
#define SOUNDCNT_H REG16(0x4000082)
#define SOUNDCNT_X REG16(0x4000084)
#define VRAM ((volatile uint16_t*) 0x6000000)
#define SRAM ((volatile uint8_t*) 0x0E000000)

#define RGB(r, g, b) ((uint16_t) ((r) | ((g) << 5) | ((b) << 10)))
#define BG RGB(3, 3, 6)
#define OFF RGB(8, 8, 14)
#define ON RGB(6, 28, 10)
#define BIT1 RGB(31, 24, 4)
#define BIT0 RGB(10, 8, 4)

// Makes mGBA's save type detection pick 32 KiB SRAM.
__attribute__((used)) const char saveTag[] = "SRAM_V113";

static void rect(int x, int y, int w, int h, uint16_t c) {
    for (int j = y; j < y + h; ++j) {
        volatile uint16_t* row = &VRAM[j * 240 + x];
        for (int i = 0; i < w; ++i) {
            row[i] = c;
        }
    }
}

static void bits(int x, int y, uint32_t value, int count, int size) {
    for (int i = 0; i < count; ++i) {
        int bit = (value >> (count - 1 - i)) & 1;
        rect(x + i * (size + 2), y, size, size, bit ? BIT1 : BIT0);
    }
}

struct Box { int16_t x, y, w, h; };
// Order matches KEYINPUT bits: A B Select Start Right Left Up Down R L
static const struct Box boxes[10] = {
    { 204, 70, 20, 20 }, { 178, 92, 20, 20 }, { 92, 132, 24, 10 }, { 124, 132, 24, 10 },
    { 52, 84, 18, 18 },  { 12, 84, 18, 18 },  { 32, 64, 18, 18 },  { 32, 104, 18, 18 },
    { 186, 44, 44, 12 }, { 10, 44, 44, 12 },
};

static const uint16_t melody[4] = { 1798, 1849, 1881, 1923 }; // C5 E5 G5 C6

static void vsync(void) {
    while (VCOUNT >= 160) {}
    while (VCOUNT < 160) {}
}

int main(void) {
    DISPCNT = 3 | (1 << 10); // mode 3, BG2
    rect(0, 0, 240, 160, BG);

    if (SRAM[1] != 'P' || SRAM[2] != 'R' || SRAM[3] != 'S') {
        SRAM[0] = 0;
        SRAM[1] = 'P';
        SRAM[2] = 'R';
        SRAM[3] = 'S';
    }

    SOUNDCNT_X = 0x80;
    SOUNDCNT_L = 0xFF77;
    SOUNDCNT_H = 0x0002;

    uint32_t frame = 0;
    uint16_t prevKeys = 0;
    int squareX = 0;
    int noteTimer = 0;
    int note = 0;

    for (;;) {
        vsync();
        uint16_t keys = ~KEYINPUT & 0x3FF;
        uint16_t pressed = keys & ~prevKeys;
        uint16_t released = prevKeys & ~keys;

        for (int i = 0; i < 10; ++i) {
            if ((keys ^ prevKeys) & (1 << i) || frame == 0) {
                rect(boxes[i].x, boxes[i].y, boxes[i].w, boxes[i].h, (keys & (1 << i)) ? ON : OFF);
            }
        }

        if (pressed & (1 << 3)) { // START: bump the persistent SRAM counter
            SRAM[0] = (uint8_t) (SRAM[0] + 1);
        }
        if (pressed & 1) { // A: 440 Hz tone on channel 1 while held
            SOUND1CNT_L = 0;
            SOUND1CNT_H = 0xF080;
            SOUND1CNT_X = 0x8000 | 1750;
        } else if (released & 1) {
            SOUND1CNT_H = 0x0080;
            SOUND1CNT_X = 0x8000 | 1750;
        }

        if (--noteTimer <= 0) { // background arpeggio on channel 2
            noteTimer = 20;
            SOUND2CNT_L = 0xA280;
            SOUND2CNT_H = 0x8000 | melody[note];
            note = (note + 1) & 3;
        }

        bits(8, 6, frame, 20, 8);           // frame counter, binary
        bits(80, 24, SRAM[0], 8, 8);        // persistent save counter
        rect(squareX, 150, 8, 8, BG);        // moving square: speed indicator
        squareX += 2;
        if (squareX > 232) squareX = 0;
        rect(squareX, 150, 8, 8, ON);

        prevKeys = keys;
        ++frame;
    }
}
