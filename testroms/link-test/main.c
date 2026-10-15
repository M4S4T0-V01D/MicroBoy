// MicroBoy link test ROM: two consoles in multiplayer mode exchange counters
// once per frame and check every value they receive. Results live at the
// start of EWRAM for tools/linktest; the screen shows green (OK) or red.
// Written for this project; public domain / CC0.
#include <stdint.h>

#define REG16(a) (*(volatile uint16_t*) (a))
#define DISPCNT REG16(0x4000000)
#define VCOUNT REG16(0x4000006)
#define SIOMULTI(n) REG16(0x4000120 + 2 * (n))
#define SIOCNT REG16(0x4000128)
#define SIOMLT_SEND REG16(0x400012A)
#define RCNT REG16(0x4000134)
#define REG_IF REG16(0x4000202)
#define VRAM ((volatile uint16_t*) 0x6000000)

// Results, read by tools/linktest.
struct Stats {
    uint32_t transfers;
    uint32_t echoErrors;     // our own slot didn't hold what we sent
    uint32_t sequenceErrors; // the partner's counter didn't advance by one
    uint32_t frames;
    uint32_t id;
    uint32_t lastPartner;
};
#define STATS ((volatile struct Stats*) 0x02000000)

static void fill(uint16_t color) {
    for (int i = 0; i < 240 * 160; ++i) VRAM[i] = color;
}

int main(void) {
    volatile struct Stats* st = STATS;
    st->transfers = st->echoErrors = st->sequenceErrors = st->frames = 0;
    st->id = 0xFF;
    DISPCNT = 0x0403;  // mode 3, BG2
    uint16_t shown = 0x3C00;
    fill(shown);       // blue: waiting for a link

    RCNT = 0;
    SIOCNT = 0x6003;   // multiplayer, 115200 baud, IRQ flag on completion
    uint16_t counter = 0;
    uint16_t lastPartner = 0;
    int havePartner = 0;
    SIOMLT_SEND = counter;
    int inVblank = 0;

    for (;;) {
        if (REG_IF & 0x80) {
            REG_IF = 0x80;
            int id = (SIOCNT >> 4) & 3;
            uint16_t mine = SIOMULTI(id);
            uint16_t partner = SIOMULTI(id ^ 1);
            st->id = id;
            ++st->transfers;
            if (mine != counter) ++st->echoErrors;
            if (havePartner && partner != (uint16_t) (lastPartner + 1)) ++st->sequenceErrors;
            lastPartner = partner;
            st->lastPartner = partner;
            havePartner = 1;
            SIOMLT_SEND = ++counter;
            uint16_t color = st->echoErrors || st->sequenceErrors ? 0x001F : 0x03E0;
            if (color != shown) fill(shown = color);
        }
        int vb = VCOUNT >= 160;
        if (vb && !inVblank) {
            ++st->frames;
            // The parent (SI low) starts one transfer per frame once everyone is ready.
            uint16_t cnt = SIOCNT;
            if (!(cnt & 0x04) && (cnt & 0x08) && !(cnt & 0x80)) {
                SIOCNT = cnt | 0x80;
            }
        }
        inVblank = vb;
    }
}
