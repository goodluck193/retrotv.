# Retro Console 1.6

A personal, noncommercial retro gaming app for Android TV 8+. Play your own NES / Dendy, Super Nintendo and Sega Mega Drive files with a DualSense controller. ARM 32-bit and 64-bit builds. **No games or game ROM downloads are included.**

## What changed

- A simpler library: select a game to resume its autosave. Hold ✕ / OK for favorites, restart or deletion. Search, recent games and console categories remain available.
- DualSense controls tailored to left-stick play, with preview rewind on D-pad Left and pause on the touchpad click.
- Black side borders by default, plus five static backgrounds: Midnight, Quiet Grid, Dunes, Forest and Arcade. Game proportions are preserved. Backgrounds allocate no image cache and run no animation loop.
- English by default; Russian, Spanish, Portuguese, French, German and Italian selectable in Settings. All application messages are translated.
- Better cover matching across regional names and punctuation, including Desert Demolition. A bundled filename index avoids online catalog searches on the TV. Images are optional, downloaded on demand and cached with fixed limits.
- An original app icon and TV launcher banner. A lightweight bilinear smoothing option; sharp pixels and 720p remain the default.
- Audio, rewind, memory limits and safe exit improvements from 1.5 / 1.5.1 are retained.

## DualSense

| Control | During a game |
| --- | --- |
| Left stick | Movement, including diagonals |
| ✕ ○ □ △ | Console face buttons |
| L2 / R2 | SNES L / R; L1 / R1 also work |
| Options / Create | Start / Select |
| Touchpad **click** | Pause menu |
| Hold D-pad ← | Rewind with preview |
| Left stick while rewinding | Adjust the selected moment; stops automatic scrubbing |
| Release D-pad ← | Continue from the selected moment |
| ○ or Back while rewinding | Cancel and return to the original moment |
| Right stick, R3, L3 | Disabled |
| TV remote Back | Pause; from the pause menu, save and return to the library |
| ○ in the pause menu | Resume |

The D-pad is reserved during gameplay because this profile uses the left stick for movement. D-pad navigation still works in menus. Touchpad swipes do nothing. The PS logo button is controlled by Android; it is not the touchpad click.

Android exposes Sony touchpad clicks as mouse-button events on many kernels. Both button and mouse-event paths are handled. Some TV firmware consumes or does not expose the touchpad device; use remote Back on such devices. Real DualSense / TV testing is still required; unit tests cannot validate vendor firmware.

## Saves, audio and resource limits

Autosave every 30 seconds and on leaving the game; three manual slots with timestamps and thumbnails. Cartridge SRAM is separate. Saves use checksums, atomic writes and a previous good backup. A changed core fingerprint is rejected instead of silently loading an incompatible state. Removing a game keeps its progress by default; reimporting the same content finds it by SHA-256.

Rewind captures every 500 ms, or 1 second on slow devices. At most 120 snapshots, including previews, fit within 1/16 of the Java heap and 24 MiB (6 MiB on low-RAM devices). Under memory pressure, history is cleared and disabled until the next launch. The core is paused during preview; the chosen state is applied only on release.

The audio pipeline uses continuous fractional resampling, a bounded PCM queue, rebuffering and a monotonic game clock. Compatible OpenSL ES audio defaults to a 100 ms app buffer; optional low latency uses 40 ms. This is buffering, not total output latency. Short scheduling gaps are absorbed; sustained insufficient CPU performance can still cause pauses.

Pause stops emulation/audio and allows the TV screensaver. **Save and exit application** closes the Android task after a bounded save wait. It does not turn off the physical TV. Android may keep a stopped process cached and reclaim it when needed. No force-kill or forced garbage collection is used.

[Audio investigation](docs/audio-investigation.md) · [Memory and exit](docs/memory-and-exit.md) · [1.6 implementation and TV checks](docs/retro-console-1.6.md)

## Import and cover art

Use **Add game** to select one file through Android's document picker. Formats: `.nes`, `.sfc`, `.smc`, `.md`, `.gen`, `.bin`, `.smd`, or ZIP containing a supported game. Files are copied into private app storage; broad storage permission is not requested.

Limits: NES 8 MiB; SNES/Sega 16 MiB; source file 32 MiB; 200 ZIP entries; 32 MiB of skipped archive data; at least 200 MiB storage reserve. The 60-second import timeout is checked between reads; a blocked document provider can delay cancellation.

Cover art is optional under Settings. Only game names are sent to GitHub / Libretro Thumbnails, never ROM contents. A missing match shows the console name. No commercial box art is bundled. Cover ownership is separate from emulator source licenses. RAM cache is bounded; disk cache is at most 64 MiB including two downloads, with a file-count limit. Clear it independently of games and saves.

## Build

Java 17, Python 3, Git, Android SDK 34, Build Tools 34.0.0, NDK 26.3.11579264 and CMake 3.22.1. Set `ANDROID_HOME` and `JAVA_HOME`.

```sh
sdkmanager 'platforms;android-34' 'build-tools;34.0.0' 'ndk;26.3.11579264' 'cmake;3.22.1'
python3 scripts/prepare_engine.py
python3 scripts/build_cores.py
python3 scripts/check_resources.py
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assemblePreview
python3 scripts/audit_apk.py app/build/outputs/apk/preview/app-preview.apk
```

Prepare the engine and cores before opening Android Studio. Engine patches live in `engine/libretrodroid.patch`; native audio/clock code in `native/`; exact core sources in `engine/cores.lock.json`. `scripts/update_cover_index.py` refreshes filename metadata during development; it never runs on the TV. Source trees for the current index are recorded in `engine/cover-sources.json`.

The normal debug package remains `com.retrotv.emu` to preserve data compatibility when signed with your existing key. The standalone **Retro Console Preview** uses `com.retrotv.emu.preview.console`, with its own library and saves. It can coexist with earlier RetroTV installations. Add your game files again to test it. **Do not uninstall an older app to resolve a signing conflict: uninstalling deletes its private games and progress.**

Preview 1.6 introduces a consistent, deliberately public **test-only** key at `config/preview-test.keystore` (alias `androiddebugkey`, password `android`). Future previews can update this package with the same signature. This is not a production identity; anyone with the public key material can sign a preview build, so install only builds you trust. A release must use your own private signing key. Earlier CI-generated private debug keys are unavailable, so 1.6 cannot update those preview packages in place or read their private saves.

GitHub Actions publishes debug/preview APKs and test reports for 90 days. It runs native audio and resource sanitizers, JVM tests, locale checks, and an APK asset audit. These tests do not replace listening and controller tests on a physical TV.

## Personal use and licenses

This project is for personal, noncommercial use. Snes9x and Genesis Plus GX impose noncommercial conditions; other components have their own obligations. Keeping the repository private does not change those conditions. Retain notices and review all licenses before redistribution or commercial use. The absence of ROMs is not a guarantee against every intellectual-property claim, and the display name is not a trademark clearance.

[Third-party sources and licenses](THIRD_PARTY.md). The app also exposes license texts in Settings. Console names identify compatible formats and do not imply affiliation with Nintendo, Sega or Sony.
