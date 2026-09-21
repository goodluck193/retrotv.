# Audio crackle investigation

Baseline: RetroTV 1.4 (`a7fefe3`). The shared frontend defects affected NES/FCEUmm, SNES/Snes9x and Mega Drive/Genesis Plus GX.

## Confirmed code defects

- The LibretroDroid callback used `0.001 * numFrames` as the controller interval: 192 frames at 48 kHz became 192 ms instead of 4 ms.
- PCM starvation inserted silence without startup accumulation or rebuffering. Resampling did not preserve fractional phase across callbacks; temporary storage depended on core rate rather than actual callback size.
- Near matching game/display rates, one game frame ran per presentation, so missed presentations reduced audio production. Catch-up was capped at two frames, and the app requested 60 Hz even for PAL.
- JNI leaked `new[]` buffers after state/SRAM serialization. Rewind repeated that leak.
- Pause stopped GLSurfaceView but left the audio stream running. A GL task exception could leave another thread waiting forever on a latch.

These shared defects explain plausible causes across all three consoles. Their individual contribution on a specific TV requires recordings and diagnostics; driver-specific problems may remain.

## Replacement pipeline

The pinned LibretroDroid source is patched with a stereo SPSC queue. The audio callback allocates no memory, performs no disk IO, and waits for neither a mutex nor the emulator. It uses the device sample rate and callback size. The resampler preserves phase; the controller uses seconds and bounds correction to ±0.4%.

Playback accumulates 100 ms PCM in compatible mode or 40 ms in low-latency mode. This is an app buffer, not total output latency. On starvation, output fades, reserve increases in 20 ms steps up to twice the initial reserve, and playback starts again after refilling. Sustained CPU starvation can still interrupt playback.

Compatible mode uses OpenSL ES. Low latency permits AAudio through Oboe with fallback if opening fails. A disconnected route reopens the stream. Diagnostics expose refills, dropped frames and device xruns.

A monotonic clock follows the core's PAL/NTSC rate independently of 50/60/120 Hz screens. Up to four frames compensate for short stalls; long pauses reset timing. There is no sleeping while holding the core mutex. Pause/background stop emulation and audio. RAII releases serialized buffers.

## Rewind and testing

Current 1.6 controls: hold D-pad Left, adjust with the left stick, release to apply, Circle/Back to cancel; touchpad click opens the menu. Capture interval is 500 ms, increased to 1 second if serialization exceeds 12 ms. Memory pressure and byte/count budgets bound history.

Only thumbnails are browsed while the core and audio are paused. Applying a state discards its future. Failed restores attempt rollback and protect existing saves.

`native/audio_test.cpp` covers 144 combinations of core rates 32040/44100/48000 Hz, game rates 50/59.94/60.0988 Hz, displays 30/50/60/120 Hz and callback sizes 96/192/512/960 frames with presentation gaps. Additional checks cover a 60000-frame callback, starvation/recovery, long pause and concurrent producer/consumer operation. ASan/UBSan run in CI; local ptrace environments may require `ASAN_OPTIONS=detect_leaks=0`.

These tests check buffering, timing and stereo integrity; they cannot hear the Android driver output.

## Physical TV protocol

1. Compatible audio, 720p, at least 10 minutes of each console with audible game sound.
2. Open Audio and memory: record backend, rates, refills, dropped frames and xruns, captured before pausing audio.
3. Repeat rewind/apply/cancel, save/load, filter changes, Home/resume, and controller disconnect/reconnect.
4. Compare available 50/60 Hz modes and the actual output route: speakers, Bluetooth or HDMI. Test low latency separately.

## Primary references

- [Original LibretroDroid audio](https://github.com/Swordfish90/LibretroDroid/blob/0.14.0/libretrodroid/src/main/cpp/audio.cpp)
- [Original frame synchronization](https://github.com/Swordfish90/LibretroDroid/blob/0.14.0/libretrodroid/src/main/cpp/fpssync.cpp)
- [Android / Oboe](https://developer.android.com/games/sdk/oboe/low-latency-audio)
- [Oboe callbacks, buffers and disconnects](https://github.com/google/oboe/blob/main/docs/FullGuide.md)
