# GBA Emulator for Android — Implementation Plan

## Goals

A native Android (Kotlin + Jetpack Compose) Game Boy Advance emulator with accurate
emulation, real fast-forward, reliable cartridge saves and save states, a game
library, and extensive customization. Ships as an installable APK.

## Environment (inspected 2026-10-15)

| Item | State | Action |
|---|---|---|
| OS | Arch Linux x86_64, 8 cores, 6 GB RAM | — |
| JDK | not installed, no sudo | Temurin 17 in `~/.jdks/` |
| Android SDK / NDK | not installed | cmdline-tools → `~/Android/Sdk` (platform 35, build-tools 35, NDK r27c, CMake 3.22) |
| KVM | available (`/dev/kvm`) | x86_64 Android emulator for testing |
| Physical device | none attached | test on emulator; APK documented for sideloading |
| Gradle | not installed | Gradle wrapper committed to the repo |

## Emulation core: mGBA 0.10.5

| Candidate | License | Notes |
|---|---|---|
| **mGBA** | **MPL-2.0** | Most accurate maintained GBA core; clean embeddable C API (`mCore`); builds as a static lib with `LIBMGBA_ONLY`; handles SRAM/Flash/EEPROM detection; HLE BIOS built in |
| VBA-M | GPL-2.0 | Less accurate, GPL would force the whole app GPL |
| gpSP | GPL-2.0 | Fast dynarec but lower accuracy |
| From scratch | — | Huge effort, worse result (explicitly ruled out) |

MPL-2.0 is file-level copyleft: we may ship mGBA inside our app under any license
as long as mGBA's source files (and any modifications to them) stay available
under MPL-2.0. mGBA is consumed unmodified as a git submodule pinned to tag
`0.10.5`, and the app shows the license in its About screen.

## Architecture

```
app/ (Kotlin, Compose)
├── ui/            Library, Game screen, Quick menu, Save manager, Settings, Game info
├── emulation/     EmulatorSession: emulation thread, pacing, fast-forward, command queue
├── render/        GLSurfaceView renderer (GLES 2): scaling, integer scale, smoothing,
│                  scanlines/CRT shader, brightness/saturation
├── input/         Touch overlay (layout editor), gamepad mapping, haptics
├── data/          Room DB (games, playtime), DataStore settings, save/state storage
└── native/        JNI bridge  ──►  libgbacore.so
                                     ├── jni_bridge.c   mCore lifecycle, frames, keys, states
                                     ├── audio.c        AAudio low-latency output + ring buffer
                                     └── third_party/mgba (static, GBA core only)
```

### Threads
- **UI thread** — Compose only; never blocks on emulation.
- **Emulation thread** — runs `runFrame()`; paced to 59.7275 Hz (GBA refresh) with a
  monotonic clock; executes queued commands (save state, reset, …) between frames.
- **GL thread** — uploads the latest completed frame (double buffer, no allocation).
- **AAudio callback thread** — pulls from a lock-free ring buffer; resampling ratio is
  nudged by buffer fill level (dynamic rate control) to avoid pops/drift.

### Fast-forward (core feature)
- Emulation thread runs `multiplier` frames per real-time frame period
  (2×, 3×, 4×, 8×, or "max" = unthrottled).
- Every frame is actually emulated; only the newest frame is presented.
- Audio is muted while fast-forwarding (configurable: mute / keep at lowered volume),
  and the ring buffer is flushed on transitions to avoid distortion.
- Activation modes: **hold** (button held) and **toggle**; both configurable.

### Storage
- ROMs stay where they are; access via SAF persisted URI permissions.
  ROM bytes are read into memory at launch (max 32 MB).
- Cartridge saves: `files/saves/<rom-sha1>.sav`, memory-mapped by mGBA, flushed on
  pause/background and atomically written (tmp + rename) on export.
- Save states: `files/states/<rom-sha1>/slot<N>.state` + `.png` thumbnail;
  slot 0 = auto-save (on background/exit), slots 1–9 manual; atomic writes.
- Settings: Jetpack DataStore. Library: Room.
- BIOS: optional user-provided `gba_bios.bin` (picked via SAF, copied to app storage);
  mGBA's HLE BIOS otherwise.

## Phases & verification

1. **Foundation** — toolchain, Gradle project, mGBA builds via NDK/CMake, baseline APK.
   *Verify:* `./gradlew assembleDebug` succeeds; APK installs on emulator.
2. **Working emulator** — JNI bridge, GL rendering, AAudio, touch input.
   *Verify:* homebrew/test ROMs (legally redistributable, e.g. jsmolka gba-tests,
   open-source homebrew) boot and render; input reaches the game.
3. **Essential features** — fast-forward, cartridge saves, save states, library, pause/reset/resume.
   *Verify:* measured FF speed > 1×; state save→load roundtrip; .sav persists across restarts.
4. **Frontend & customization** — full UI, layout editor, filters, controller mapping, settings.
   *Verify:* settings persist across restart; portrait + landscape screenshots.
5. **Testing & release** — compatibility, lifecycle, performance, signed release APK.
   *Verify:* release APK builds and installs; README documents install.

## Out of scope / not shipped
- No commercial ROMs, no proprietary BIOS.
- Game Boy / GBC games (mGBA supports them; GB core disabled to keep the app focused).
- Link cable, netplay, cheats (possible later).
