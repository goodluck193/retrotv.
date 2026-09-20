# Memory limits and exit behavior

The 1.5.1 investigation fixed retained native ROM buffers after repeated launches, skipped native destruction after startup errors, an unbounded save queue, and missing memory-pressure cleanup. These fixes remain in 1.6. The core, Java/Android, ROM, OpenGL and audio driver also consume memory: a rewind budget is not a cap on total process RSS.

## Bounds and ownership

- Rewind snapshots plus thumbnails: at most 1/16 Java heap, 24 MiB normally or 6 MiB on low-RAM devices, and 120 entries. Oldest entries are freed first; backgrounding clears history.
- MemoryInfo and available Java heap are checked every two seconds in gameplay. Low memory clears optional data and disables rewind until the next launch. Trim/low-memory callbacks supplement polling; recent Android versions do not provide all legacy running-pressure notifications.
- Cover RAM cache: 1/32 heap, capped at 12 MiB normally or 4 MiB for low-RAM devices. Leaving the library cancels its jobs, releases ImageViews and recycler holders, and evicts cache. Epoch checks prevent an older download from repopulating a cleared cache.
- Cover disk cache: 56 MiB target plus two downloads of at most 4 MiB each, with a file-count limit including negative-cache markers. Startup removes stale partial downloads. Clearing covers never deletes games or saves.
- Cover index: streamed gzip filename metadata on IO, at most two concurrent lookups, no retained catalog map, no runtime online index scan. Static side backgrounds use primitive drawing without bitmaps, timers or animation.
- Save work: at most three outstanding jobs and 1/8 heap capped at 16 MiB of payloads. A full queue rejects new work with a message. Disk writes are serialized away from the UI; flush waits have deadlines.
- Saves preserve a 64 MiB storage reserve including the new file and prior-state backup. Covers/import keep 200 MiB. Other applications can still consume storage independently.
- Previous-state CRC validation streams 8 KiB blocks instead of allocating another full state.
- Native ROM data has one owner until unload. Files, descriptors and buffers are released on failure as well as success. Unknown, negative or oversized input is rejected before allocation; native ROM/state/SRAM is bounded at 32 MiB.
- The GL thread has normal priority. Per-frame coroutine notifications were removed; aborted startup no longer skips cleanup.

## Pause and exit

Touchpad click or remote Back opens pause. Save and exit application waits for a save for up to three seconds, then closes the Android task. Save and return to library leaves the player. The library also has an Exit button. No force-kill or forced GC is used. A slow or interrupted write cannot guarantee the latest moment, but the previous valid generation is protected.

Home/background stops emulation and audio, cancels periodic work, clears history and requests autosave in onStop. Pause/background/exit clear keep-screen-on, allowing system sleep/screensaver. The application does not power off the television. Android can retain a stopped process in cache and reclaim it later.

## Verification and limits

Native tests perform 1000 ROM path/descriptor ownership cycles, missing/empty/oversized input checks and error-path descriptor cleanup under sanitizers. JVM checks cover 10000 history entries, preview disposal, 10000 queue rejections, concurrent budget accounting, low-RAM budgets, disk reserves, and cache file eviction. Audio and save-integrity tests remain enabled.

A physical TV, its firmware, drivers and DualSense are not available in CI. No absolute absence of hangs is claimed. A Java timeout cannot interrupt a stuck native function or blocked hardware IO. Bounds reduce avoidable load but do not replace device measurement.

On TV: play for an hour with rewind; repeat 30 launches/exits across all consoles; test Home/resume, controller disconnect and app Exit. Compare `dumpsys meminfo` after warm-up and repeated launches for sustained growth. Verify saved progress, silent background operation and the screensaver during pause. If ADB is available, `am send-trim-memory` can exercise cleanup.

Preview 1.6 uses `com.retrotv.emu.preview.console`, separate from older RetroTV previews. It does not erase their games or progress. See README for signing and update constraints.

References: [Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle), [Android memory](https://developer.android.com/topic/performance/memory-management), [ComponentCallbacks2](https://developer.android.com/reference/android/content/ComponentCallbacks2).
