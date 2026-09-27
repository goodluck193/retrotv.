# Memory limits and exit behavior

The 1.5.1 investigation fixed retained native ROM buffers after repeated launches, skipped native destruction after startup errors, an unbounded save queue, and missing memory-pressure cleanup. These fixes remain in 1.6.3. The core, Java/Android, ROM, OpenGL and audio driver also consume memory: a rewind budget is not a cap on total process RSS.

## Bounds and ownership

- Rewind snapshots plus thumbnails: at most 1/16 Java heap, 24 MiB normally or 6 MiB on low-RAM devices, and 120 entries. Oldest entries are released before allocating the next snapshot; backgrounding clears history. One in-flight capture is allowed. Snapshot size is queried under the core lock without allocating a state.
- MemoryInfo and available Java heap are checked every two seconds in gameplay, and immediately before checkpoint allocation and rewind entry/commit. Java headroom is at least 16 MiB or 1/8 heap; system headroom is at least 64 MiB or the Android low-memory threshold. Temporary native/Java snapshot copies and a pending preview are included in preflight. These checks are conservative estimates, not atomic reservations against other apps. Low memory clears optional data and disables rewind until the next launch. Trim/low-memory callbacks supplement polling; recent Android versions do not provide all legacy running-pressure notifications.
- Cover RAM cache: 1/32 heap, capped at 12 MiB normally or 4 MiB for low-RAM devices. Leaving the library cancels its jobs, releases ImageViews and recycler holders, and evicts cache. Epoch checks prevent an older download from repopulating a cleared cache.
- Cover disk cache: 56 MiB target plus two downloads of at most 4 MiB each, with a file-count limit including negative-cache markers. Startup removes stale partial downloads. Clearing covers never deletes games or saves.
- Cover index: streamed gzip filename metadata on IO, at most two concurrent lookups, no retained catalog map, no runtime online index scan. Static side backgrounds repeat a small 120 dp vector tile. Android may cache one tile; there is no full-screen wallpaper bitmap, timer or animation.
- Save work: at most three outstanding jobs and 1/8 heap capped at 16 MiB of payloads. A full queue rejects new work with a message. Disk writes are serialized away from the UI; flush waits have deadlines.
- Saves preserve a 64 MiB storage reserve including the new file and prior-state backup. Covers/import keep 200 MiB. Other applications can still consume storage independently.
- Previous-state CRC validation streams 8 KiB blocks instead of allocating another full state.
- Native ROM data has one owner until unload. Files, descriptors and buffers are released on failure as well as success. Unknown, negative or oversized input is rejected before allocation; native ROM/state/SRAM is bounded at 32 MiB.
- The GL thread has normal priority. Per-frame coroutine notifications were removed; aborted startup no longer skips cleanup.

## Pause and exit

D-pad Up, an available touchpad click or remote Back opens pause. Return to library leaves the player without making a new save, including suppressing its onStop autosave. Existing manual/periodic writes may complete. The library has a separate Exit button that waits boundedly for already queued writes. No force-kill or forced GC is used. A slow or interrupted write cannot guarantee the latest moment, but the previous valid generation is protected.

Home/background stops emulation and audio, cancels periodic work, clears history and requests autosave in onStop. Pause/background/exit clear keep-screen-on, allowing system sleep/screensaver. The application does not power off the television. Android can retain a stopped process in cache and reclaim it later.

## Verification and limits

Native tests perform 1000 ROM path/descriptor ownership cycles, missing/empty/oversized input checks and error-path descriptor cleanup under sanitizers. JVM checks cover 100000 mixed history additions, timeline branching and clearing with exactly-once preview disposal, allocation preflight, rejected/partly-applied states, injected OOM and rollback failure, 10000 queue rejections, concurrent budget accounting, low-RAM budgets, disk reserves, and cache file eviction. A separate JNI sanitizer test exercises 30000 acquire/release cycles including rejected states and C++ exceptions, then injects a null allocation result and invalid input sizes. Audio and save-integrity tests remain enabled.

A physical TV, its firmware, drivers and DualSense are not available in CI. No absolute absence of hangs is claimed. A Java timeout cannot interrupt a stuck native function or blocked hardware IO. Bounds reduce avoidable load but do not replace device measurement.

On TV: play for an hour with rewind; repeat 30 launches/exits across all consoles; test Home/resume, controller disconnect and app Exit. Compare `dumpsys meminfo` after warm-up and repeated launches for sustained growth. Verify saved progress, silent background operation and the screensaver during pause. If ADB is available, `am send-trim-memory` can exercise cleanup.

Preview 1.6 uses `com.retrotv.emu.preview.console`, separate from older RetroTV previews. It does not erase their games or progress. See README for signing and update constraints.

References: [Activity lifecycle](https://developer.android.com/guide/components/activities/activity-lifecycle), [Android memory](https://developer.android.com/topic/performance/memory-management), [ComponentCallbacks2](https://developer.android.com/reference/android/content/ComponentCallbacks2).

## 1.6.3 audit findings

The previous JNI restore path did not check whether GetByteArrayElements returned null, and a C++ exception could bypass ReleaseByteArrayElements. The bridge now uses an RAII guard, rejects invalid sizes, preserves a pending JVM exception and never invokes the core with null data. Kotlin rewind now handles OutOfMemoryError during restore, drops optional history before rollback and always releases the origin reference. A failed rollback protects saves and exits instead of resuming an uncertain core. Cancel still leaves the original moment untouched because browsing previews never changes the core.

Upgrading preserves the package/signature and the three emulator-core binaries per ABI; only the shared frontend engine bridge changes. Manual saves and existing automatic saves remain as before.

Implementation reference: [Android JNI array ownership and pending exceptions](https://developer.android.com/ndk/guides/jni-tips).
