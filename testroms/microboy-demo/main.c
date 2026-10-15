// MicroBoy demo ROM: the pixel scene from the MicroBoy website, as an endless
// GBA program. Three parallax ridges scroll forever on hardware backgrounds,
// stars twinkle, the title bobs and a frame counter ticks, so fast-forward in
// an emulator is easy to see. "Pocket Lullaby", a slow looping tune for all
// four GBA sound channels, plays on the PSG.
//
// Controls: A / Right next theme, B / Left previous theme, Start toggles music.
// Written for this project; public domain / CC0.
#include <stdint.h>

#include "scene.h"

#define REG16(a) (*(volatile uint16_t*) (a))
#define REG32(a) (*(volatile uint32_t*) (a))
#define DISPCNT REG16(0x4000000)
#define VCOUNT REG16(0x4000006)
#define BGCNT(n) REG16(0x4000008 + 2 * (n))
#define BGHOFS(n) REG16(0x4000010 + 4 * (n))
#define BGVOFS(n) REG16(0x4000012 + 4 * (n))
#define SOUND1CNT_L REG16(0x4000060)
#define SOUND1CNT_H REG16(0x4000062)
#define SOUND1CNT_X REG16(0x4000064)
#define SOUND2CNT_L REG16(0x4000068)
#define SOUND2CNT_H REG16(0x400006C)
#define SOUNDCNT_L REG16(0x4000080)
#define SOUNDCNT_H REG16(0x4000082)
#define SOUNDCNT_X REG16(0x4000084)
#define KEYINPUT REG16(0x4000130)
#define WAITCNT REG16(0x4000204)

#define BG_PAL ((volatile uint16_t*) 0x5000000)
#define OBJ_PAL ((volatile uint16_t*) 0x5000200)
#define VRAM32 ((volatile uint32_t*) 0x6000000)
#define SCREENBLOCK(n) ((volatile uint16_t*) (0x6000000 + (n) * 0x800))
#define OBJ_VRAM32 ((volatile uint32_t*) 0x6010000)
#define OAM ((volatile uint16_t*) 0x7000000)

#define KEY_A 0x001
#define KEY_B 0x002
#define KEY_START 0x008
#define KEY_RIGHT 0x010
#define KEY_LEFT 0x020

// Layers, front to back: near ridge + ground, mid ridge, far ridge, sky.
enum { BG_NEAR, BG_MID, BG_FAR, BG_SKY };
static const uint8_t screenblock[4] = { 24, 26, 28, 30 };
// Scroll speed in 1/256 pixel per frame, matching the website's rates.
static const uint16_t scrollRate[3] = { 307, 154, 64 };

// OBJ tiles: title sprites first, then the two text strips.
#define TITLE_TILE 0
#define COUNTER_TILE 64  // 8 tiles = 64×8 pixels
#define NAME_TILE 72     // 8 tiles = 64×8 pixels
#define STRIP_TILES 8

static uint32_t stripBuf[STRIP_TILES * 8];

static void vsync(void) {
    while (VCOUNT >= 160) {}
    while (VCOUNT < 160) {}
}

static void copy32(volatile uint32_t* dst, const uint32_t* src, int words) {
    for (int i = 0; i < words; ++i) dst[i] = src[i];
}

// --- text strips (64×8 sprites drawn in software) ----------------------------

static void stripClear(void) {
    for (int i = 0; i < STRIP_TILES * 8; ++i) stripBuf[i] = 0;
}

static void stripPixel(int x, int y, int color) {
    if (x < 0 || x >= STRIP_TILES * 8 || y < 0 || y >= 8) return;
    uint32_t* row = &stripBuf[(x >> 3) * 8 + y];
    int shift = (x & 7) * 4;
    *row = (*row & ~(0xFu << shift)) | ((uint32_t) color << shift);
}

static void stripText(const char* s, int x, int color) {
    for (; *s; ++s, x += 4) {
        int g = 0;
        for (int i = 0; fontChars[i]; ++i) {
            if (fontChars[i] == *s) { g = i; break; }
        }
        uint16_t bits = fontGlyphs[g];
        for (int i = 0; i < 15; ++i) {
            if (bits & (1 << i)) {
                stripPixel(x + i % 3 + 1, i / 3 + 2, OBJ_SHADOW);
                stripPixel(x + i % 3, i / 3 + 1, color);
            }
        }
    }
}

static void stripUpload(int tile) {
    copy32(&OBJ_VRAM32[tile * 8], stripBuf, STRIP_TILES * 8);
}

// --- sprites -----------------------------------------------------------------

static void setSprite(int n, int x, int y, int shape, int size, int tile) {
    volatile uint16_t* o = &OAM[n * 4];
    o[0] = (uint16_t) ((y & 0xFF) | (shape << 14));
    o[1] = (uint16_t) ((x & 0x1FF) | (size << 14));
    o[2] = (uint16_t) tile;  // priority 0, palette 0
}

static void hideSprite(int n) {
    OAM[n * 4] = 0x0200;  // OBJ disable
}

// --- themes --------------------------------------------------------------------

static void loadTheme(int t) {
    const uint16_t* p = &themePalettes[t * 5 * 16];
    for (int i = 0; i < 4 * 16; ++i) BG_PAL[i] = p[i];
    for (int i = 0; i < 16; ++i) OBJ_PAL[i] = p[4 * 16 + i];
}

static void showName(int t) {
    stripClear();
    stripText(themeNames[t], 0, OBJ_TEXT);
    stripUpload(NAME_TILE);
}

// --- music -----------------------------------------------------------------------
// "Pocket Lullaby" (see gen.py): one 16th-note step every FRAMES_PER_STEP
// frames, about 82 BPM. Each track holds a register value per step:
// 0 = keep playing, 0xFFFF = silence.

#define FRAMES_PER_STEP 11
#define SOUND3CNT_L REG16(0x4000070)
#define SOUND3CNT_H REG16(0x4000072)
#define SOUND3CNT_X REG16(0x4000074)
#define SOUND4CNT_L REG16(0x4000078)
#define SOUND4CNT_H REG16(0x400007C)
#define WAVE_RAM ((volatile uint16_t*) 0x4000090)

#define ENV(vol, step, up) ((uint16_t) (((vol) << 12) | ((up) << 11) | ((step) << 8)))
#define DUTY_12 (0 << 6)
#define DUTY_25 (1 << 6)

static void loadWave(void) {
    SOUND3CNT_L = 0x40;  // play bank 1 so writes land in bank 0
    for (int i = 0; i < 8; ++i) {
        WAVE_RAM[i] = (uint16_t) (waveTable[2 * i] | (waveTable[2 * i + 1] << 8));
    }
    SOUND3CNT_L = 0x80;  // channel on, one 32-sample bank (bank 0)
}

static void playStep(int step) {
    uint16_t lead = leadTrack[step];
    if (lead == 0xFFFF) {
        SOUND1CNT_H = ENV(0, 0, 0);  // rest
        SOUND1CNT_X = 0x8000;
    } else if (lead) {
        // Music-box pluck: starts at volume 11 and fades over about a second.
        SOUND1CNT_H = DUTY_25 | ENV(11, 5, 0);
        SOUND1CNT_X = 0x8000 | lead;
    }
    uint16_t harm = harmTrack[step];
    if (harm) {
        SOUND2CNT_L = DUTY_12 | ENV(5, 2, 0);
        SOUND2CNT_H = 0x8000 | harm;
    }
    uint16_t bass = bassTrack[step];
    if (bass) {
        SOUND3CNT_H = 2 << 13;  // 50% volume
        SOUND3CNT_X = 0x8000 | bass;
    }
    switch (drumTrack[step]) {
    case 1:  // hi-hat: high, short
        SOUND4CNT_L = ENV(3, 1, 0);
        SOUND4CNT_H = 0x8000 | (1 << 4) | 0;
        break;
    case 2:  // soft snare: lower, a little longer
        SOUND4CNT_L = ENV(6, 2, 0);
        SOUND4CNT_H = 0x8000 | (4 << 4) | 2;
        break;
    }
}

static void soundOn(int on) {
    if (on) {
        SOUNDCNT_X = 0x80;    // master enable first, or the other registers ignore writes
        SOUNDCNT_L = 0xFF77;  // all four channels on both sides, master volume 7
        SOUNDCNT_H = 0x0002;  // PSG at 100%
        SOUND1CNT_L = 0;      // no sweep
        loadWave();
    } else {
        SOUNDCNT_X = 0;
    }
}

// --- counter ---------------------------------------------------------------------

// The GBA's ARM7 has no divide instruction, so the counter is kept as decimal
// digits and wraps after 99999 like the website's. It lives in .bss (the
// linker script has no .data section) and is filled in by main().
static char counterText[12];

static void tickCounter(void) {
    for (int i = 10; i >= 6; --i) {
        if (++counterText[i] <= '9') return;
        counterText[i] = '0';
    }
}

static void drawCounter(void) {
    stripClear();
    stripText(counterText, 0, OBJ_COUNTER);
}

int main(void) {
    WAITCNT = 0x4317;  // faster ROM access
    DISPCNT = 0x0080;  // forced blank while loading

    copy32(VRAM32, bgTiles, BG_TILE_COUNT * 8);
    const uint16_t* maps[4] = { map_near, map_mid, map_far, map_sky };
    const int blocks[4] = { 2, 2, 2, 1 };
    for (int l = 0; l < 4; ++l) {
        volatile uint16_t* sb = SCREENBLOCK(screenblock[l]);
        for (int i = 0; i < blocks[l] * 1024; ++i) sb[i] = maps[l][i];
        // priority = layer index, char base 0, 4bpp, size 512×256 (or 256×256 for the sky)
        BGCNT(l) = (uint16_t) (l | (screenblock[l] << 8) | ((blocks[l] == 2 ? 1 : 0) << 14));
        BGHOFS(l) = 0;
        BGVOFS(l) = 0;
    }

    copy32(OBJ_VRAM32, titleTiles, 64 * 8);
    for (int i = 0; i < 128; ++i) hideSprite(i);

    int theme = 0;
    loadTheme(theme);
    showName(theme);

    // BG0-3 + OBJ, 1D sprite mapping, mode 0.
    DISPCNT = 0x1F40;

    int music = 1;
    soundOn(music);

    for (int i = 0; i < 11; ++i) counterText[i] = "FRAME 00000"[i];
    int bobIndex = 0, twinkleIndex = 0;
    uint32_t scroll[3] = { 0, 0, 0 };
    uint16_t prevKeys = 0;
    int nameTimer = 180;
    int step = 0, stepTimer = 0;

    for (;;) {
        drawCounter();  // render into RAM before vblank, upload during it
        vsync();

        stripUpload(COUNTER_TILE);
        for (int l = 0; l < 3; ++l) BGHOFS(l) = (uint16_t) (scroll[l] >> 8);

        int bob = bobTable[bobIndex];
        for (int i = 0; i < 4; ++i) setSprite(i, 72 - 8 + i * 32, 20 - 8 + bob, 0, 2, TITLE_TILE + i * 16);
        setSprite(4, 6, 5, 1, 1, COUNTER_TILE);
        setSprite(5, 6 + 32, 5, 1, 1, COUNTER_TILE + 4);
        if (nameTimer > 0) {
            setSprite(6, 6, 148, 1, 1, NAME_TILE);
            setSprite(7, 6 + 32, 148, 1, 1, NAME_TILE + 4);
        } else {
            hideSprite(6);
            hideSprite(7);
        }

        // Stars of the twinkling kind blink off for 6 frames in every 50.
        BG_PAL[STAR_TWINKLE] = twinkleIndex < 44 ? themePalettes[theme * 5 * 16 + STAR_TWINKLE] : twinkleDim[theme];

        // --- logic for the next frame ---
        for (int l = 0; l < 3; ++l) scroll[l] = (scroll[l] + scrollRate[l]) & ((512u << 8) - 1);
        if (nameTimer > 0) --nameTimer;

        uint16_t keys = (uint16_t) (~KEYINPUT & 0x3FF);
        uint16_t pressed = keys & ~prevKeys;
        prevKeys = keys;
        if (pressed & (KEY_A | KEY_RIGHT)) theme = theme == THEME_COUNT - 1 ? 0 : theme + 1;
        if (pressed & (KEY_B | KEY_LEFT)) theme = theme == 0 ? THEME_COUNT - 1 : theme - 1;
        if (pressed & (KEY_A | KEY_B | KEY_LEFT | KEY_RIGHT)) {
            loadTheme(theme);
            showName(theme);
            nameTimer = 120;
        }
        if (pressed & KEY_START) {
            music = !music;
            soundOn(music);
            stepTimer = 0;
            step = 0;  // the song restarts from the top
        }

        if (music && stepTimer-- <= 0) {
            playStep(step);
            if (++step == SONG_STEPS) step = 0;
            stepTimer = FRAMES_PER_STEP - 1;
        }
        tickCounter();
        if (++bobIndex == 112) bobIndex = 0;
        if (++twinkleIndex == 50) twinkleIndex = 0;
    }
}
