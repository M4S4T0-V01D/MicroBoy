// Low-latency audio output: AAudio stream fed from a lock-free SPSC ring buffer.
//
// Producer: the emulation thread (audio_push).
// Consumer: the AAudio real-time callback thread.
#pragma once

#include <stdbool.h>
#include <stdint.h>
#include <stddef.h>

bool audio_open(void);
void audio_close(void);
void audio_pause(void);
void audio_resume(void);

// Re-opens the stream if the device was disconnected (e.g. headphones unplugged).
// Must be called from the producer thread, never from the callback.
void audio_service(void);

void audio_push(const int16_t* stereo, size_t frames);
void audio_flush(void);

int audio_sample_rate(void);
size_t audio_fill(void);
size_t audio_target_fill(void);

void audio_set_volume(float volume); // 0..1
uint32_t audio_underruns(void);
