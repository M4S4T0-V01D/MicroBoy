"""A small composer for the GBA's four PSG channels.

Each song is 16 bars of 16th notes in the form A A' B A'':
  A   a 4-bar phrase over the A chords;
  A'  the same melody re-fitted to the next 4 chords;
  B   a new, slightly higher phrase over the B chords;
  A'' the opening phrase again, resolving to the tonic.
Melodies move mostly by step, land on chord tones on strong beats and end
phrases on the root or third, which keeps generated tunes singable.

Output per step (main.c reads these): square/wave frequency registers,
0 = keep playing, 0xFFFF = silence; drums 0 none, 1 hat, 2 snare, 3 kick, 4 open hat.
"""
import math
import random

SCALES = {
    "major": [0, 2, 4, 5, 7, 9, 11],
    "minor": [0, 2, 3, 5, 7, 8, 10],
    "dorian": [0, 2, 3, 5, 7, 9, 10],
    "lydian": [0, 2, 4, 6, 7, 9, 11],
    "mixolydian": [0, 2, 4, 5, 7, 9, 10],
    "phrygian": [0, 1, 3, 5, 7, 8, 10],
    "harmonic": [0, 2, 3, 5, 7, 8, 11],
}

NOTES = {"C": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3, "E": 4, "F": 5, "F#": 6, "Gb": 6,
         "G": 7, "G#": 8, "Ab": 8, "A": 9, "A#": 10, "Bb": 10, "B": 11}

# Rhythm templates for one bar (16 steps); negative = rest.
RHYTHMS = {
    "gentle": [[4, 2, 2, 4, 4], [4, 4, 6, -2], [2, 2, 2, 2, 4, 4], [6, 2, 4, 4], [8, 4, 4], [4, 4, 4, 4]],
    "bouncy": [[2, 2, 4, 2, 2, 4], [4, 2, 2, 4, -2, 2], [2, 2, 2, 2, 2, 2, 4], [3, 3, 2, 4, 4], [2, 2, 4, 4, 4]],
    "dreamy": [[8, 8], [4, 4, 8], [6, 2, 8], [12, 4], [4, 12]],
    "synth": [[3, 3, 2, 4, 4], [2, 2, 3, 3, 2, 4], [4, 2, 2, 2, 2, 4], [3, 3, 4, 2, 4], [6, 2, 2, 2, 4]],
    "drive": [[2, 2, 2, 2, 4, 4], [3, 3, 2, 2, 2, 4], [2, 2, 2, 2, 2, 2, 2, 2], [4, 2, 2, 4, 4]],
    "lofi": [[4, -2, 2, 4, 4], [3, 3, -2, 4, 4], [6, -2, 2, 2, 4], [2, 2, 4, -4, 4]],
}
ENDINGS = {
    "gentle": [[4, 4, 8], [12, -4], [8, 8]],
    "bouncy": [[2, 2, 4, 8], [4, 4, 8]],
    "dreamy": [[16], [8, 8]],
    "synth": [[4, 4, 8], [6, 2, 8]],
    "drive": [[4, 4, 8], [2, 2, 4, 8]],
    "lofi": [[4, 4, 8], [6, -2, 8]],
}

DRUMS = {
    "none": "................",
    "lullaby": "........h.......",
    "soft": "..h...h...h...h.",
    "skip": "h.h.h.h.h.h.h.h.",
    "lofi": "k.h.s.hkh.k.s.h.",
    "four": "k.h.s.h.k.h.s.ho",
    "break": "k.h.s.hkk.h.s.ho",
    "drive": "k.hhs.hhk.hhs.hh",
    "march": "k...s...k.k.s...",
}
FILL = "s.s.s.sss.s.ssss"

WAVES = {
    "sine": [round(7.5 + 7.5 * math.sin(2 * math.pi * (i + 0.5) / 32) * 0.95) for i in range(32)],
    "triangle": [round(15 * (1 - abs((i + 0.5) / 16 - 1))) for i in range(32)],
    "saw": [i // 2 for i in range(32)],
    "square": [15 if i < 16 else 0 for i in range(32)],
    "organ": [round(7.5 + 4.5 * math.sin(2 * math.pi * i / 32) + 3 * math.sin(4 * math.pi * i / 32)) for i in range(32)],
}


def hz(m):
    return 440 * 2 ** ((m - 69) / 12)


def sq(m):
    return max(0, min(2047, round(2048 - 131072 / hz(m))))


def wv(m):
    return max(0, min(2047, round(2048 - 65536 / hz(m))))


class Song:
    def __init__(self, key="C4", scale="major", prog_a=(0, 5, 3, 4), prog_b=(3, 4, 5, 4), tempo=9, swing=0,
                 rhythm="gentle", lead_range=(0, 9), lead=(1, 11, 5), harm_style="arp8", harm=(0, 5, 2),
                 bass_style="bounce", wave="sine", bass_vol=2, drums="soft", drums_b=None, fill=False,
                 penta=False, seed=1, sustain=False):
        self.key = NOTES[key[:-1]] + 12 * (int(key[-1]) + 1)
        self.scale = SCALES[scale]
        self.prog_a, self.prog_b = list(prog_a), list(prog_b)
        self.tempo, self.swing = tempo, swing
        self.rhythm = rhythm
        self.lead_range = lead_range          # scale degrees relative to key
        self.lead = lead                      # (duty, env volume, env step; step 0 = sustain)
        self.harm_style, self.harm = harm_style, harm
        self.bass_style, self.wave, self.bass_vol = bass_style, wave, bass_vol
        self.drums, self.drums_b, self.fill = drums, drums_b or drums, fill
        self.penta, self.seed = penta, seed
        self.sustain = sustain or lead[2] == 0

    # --- pitch helpers ---------------------------------------------------------------
    def midi(self, d):
        return self.key + 12 * (d // 7) + self.scale[d % 7]

    def chord_tones(self, root_deg):
        return {(root_deg + i) % 7 for i in (0, 2, 4)}

    def nearest_tone(self, d, chord, prefer=None):
        tones = self.chord_tones(chord)
        best = None
        for off in (0, -1, 1, -2, 2, -3, 3):
            if (d + off) % 7 in tones and (prefer is None or (d + off) % 7 in prefer):
                return d + off
            if best is None and (d + off) % 7 in tones:
                best = d + off
        return best if best is not None else d

    # --- melody -------------------------------------------------------------------------
    def phrase(self, chords, rng, start, lift=0, end_tonic=False):
        lo, hi = self.lead_range[0] + lift, self.lead_range[1] + lift
        notes = []  # (step, length, degree or None)
        cur = start
        templates = RHYTHMS[self.rhythm]
        motif = rng.choice(templates)
        for bar, chord in enumerate(chords):
            if bar == 3:
                rhythm = rng.choice(ENDINGS[self.rhythm])
            elif bar == 2:
                rhythm = rng.choice(templates)
            else:
                rhythm = motif  # bars 1 and 2 share a rhythm: a motif you can hum
            pos = 0
            for k, dur in enumerate(rhythm):
                step = bar * 16 + pos
                pos += abs(dur)
                if dur < 0:
                    notes.append((step, -dur, None))
                    continue
                half = bar < 2
                bias = [0.12, 0.28, 0.12, 0.33, 0.15] if half else [0.15, 0.33, 0.12, 0.28, 0.12]
                move = rng.choices([-2, -1, 0, 1, 2], bias)[0]
                if not notes:
                    cand = self.nearest_tone(start, chord)
                else:
                    cand = cur + move
                strong = (step % 16) in (0, 8)
                last = bar == 3 and pos >= 16
                if strong or last:
                    prefer = {chord % 7, (chord + 2) % 7} if last else None
                    if last and end_tonic:
                        prefer = {0}
                    cand = self.nearest_tone(cand, chord, prefer)
                elif self.penta and self.scale[cand % 7] in (5, 11, 6):
                    cand += 1 if move >= 0 else -1
                while cand > hi:
                    cand -= 2
                while cand < lo:
                    cand += 2
                cur = cand
                notes.append((step, dur, cand))
        return notes

    def refit(self, notes, chords, end_tonic=False):
        out = []
        for i, (step, dur, d) in enumerate(notes):
            if d is not None:
                chord = chords[(step // 16) % 4]
                last = i == len(notes) - 1 or all(n[2] is None for n in notes[i + 1:])
                if step % 16 in (0, 8) or last:
                    d = self.nearest_tone(d, chord, {0} if (last and end_tonic) else None)
            out.append((step, dur, d))
        return out

    def compose(self):
        rng = random.Random(self.seed)
        a_chords = self.prog_a
        start = (self.lead_range[0] + self.lead_range[1]) // 2
        A = self.phrase(a_chords, rng, start)
        # A': the opening two bars lifted a third, then fitted back to the chords.
        A2 = self.refit([(st, du, (d + 2 if (d is not None and st < 32) else d)) for st, du, d in A], self.prog_a)
        B = self.phrase(self.prog_b, rng, start + 2, lift=2)
        A3 = self.refit(A, a_chords, end_tonic=True)
        bars_chords = a_chords + a_chords + self.prog_b + a_chords
        lead = [0] * 256
        for off, ph in ((0, A), (64, A2), (128, B), (192, A3)):
            for step, dur, d in ph:
                s = off + step
                if d is None:
                    lead[s] = 0xFFFF
                else:
                    lead[s] = sq(self.midi(d))
        return lead, bars_chords

    # --- backing -----------------------------------------------------------------------
    def voicing(self, chord, low, high):
        out = []
        for i in (0, 2, 4):
            m = self.midi(chord + i)
            while m < low:
                m += 12
            while m >= high:
                m -= 12
            out.append(m)
        return sorted(out)

    def backing(self, bars_chords):
        harm = [0] * 256
        bass = [0] * 256
        drums = [0] * 256
        for bar, chord in enumerate(bars_chords):
            s0 = bar * 16
            lo, mid, hi = self.voicing(chord, 53, 66)  # F3..F#4
            st = self.harm_style
            if st == "arp8":
                pat = [lo, hi, mid, hi]
                for i in range(8):
                    harm[s0 + i * 2] = sq(pat[i % 4])
            elif st == "arp16":
                pat = [lo, mid, hi, mid + 12 if mid + 12 < 80 else hi]
                for i in range(16):
                    harm[s0 + i] = sq(pat[i % 4])
            elif st == "stab":
                for i, s in enumerate((2, 6, 10, 14)):
                    harm[s0 + s] = sq(mid if i % 2 == 0 else hi)
            elif st == "pad":
                harm[s0] = sq(mid)
            elif st == "updown":
                pat = [lo, mid, hi, mid]
                for i in range(8):
                    harm[s0 + i * 2] = sq(pat[i % 4] + 12)
            root = self.midi(chord)
            while root >= 52:
                root -= 12
            while root < 40:
                root += 12
            fifth = root + 7
            bst = self.bass_style
            if bst == "bounce":
                for i, m in enumerate((root, fifth, root + 12, fifth)):
                    bass[s0 + i * 4] = wv(m)
            elif bst == "pump":
                for i in range(8):
                    bass[s0 + i * 2] = wv(root if i % 2 == 0 else root + 12)
            elif bst == "long":
                bass[s0] = wv(root)
                bass[s0 + 8] = wv(fifth)
            elif bst == "walk":
                nxt = bars_chords[(bar + 1) % len(bars_chords)]
                nroot = self.midi(nxt)
                while nroot >= 52:
                    nroot -= 12
                while nroot < 40:
                    nroot += 12
                for i, m in enumerate((root, root + (4 if (self.scale[2] == 4) else 3), fifth, nroot - 1 if nroot > root else nroot + 1)):
                    bass[s0 + i * 4] = wv(m)
            elif bst == "sync":
                for s, m in ((0, root), (6, root), (10, fifth), (14, root + 12)):
                    bass[s0 + s] = wv(m)
            pattern = DRUMS[self.drums_b if 8 <= bar < 12 else self.drums]
            if self.fill and bar in (7, 15):
                pattern = FILL
            for i, ch in enumerate(pattern):
                drums[s0 + i] = {"h": 1, "s": 2, "k": 3, "o": 4}.get(ch, 0)
        return harm, bass, drums

    def build(self):
        lead, chords = self.compose()
        harm, bass, drums = self.backing(chords)
        wave = WAVES[self.wave]
        wave_bytes = [(wave[i] << 4) | wave[i + 1] for i in range(0, 32, 2)]
        return {"lead": lead, "harm": harm, "bass": bass, "drums": drums, "wave": wave_bytes}
