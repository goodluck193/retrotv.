# Retro Console 1.6 implementation notes

## Input

The user's profile reserves D-pad Left for rewind and uses only the left stick for movement. R3/L3 do nothing. Right-stick Z/RZ axes never enter the gameplay pipeline. Whitelisted face, Start and Select buttons reach the core; L2/R2 are mapped to L1/R1's libretro shoulder IDs because libretro L2/R2 do not represent SNES L/R.

A source-bit latch combines digital and analog trigger events, so releasing L2 does not release a still-held L1. Rewind similarly combines key and HAT_X paths without repeated starts from key repeat. Cancel leaves the latch held until release. Pausing releases forwarded buttons and sends a neutral direction.

Sony touchpad BTN_LEFT can arrive as a key scan code, mouse primary-button event or touchpad event. These paths are handled only for identified Sony / DualSense / Wireless Controller devices. Swiping does not move the game. The app cannot intercept events a TV firmware consumes before delivery. Remote Back remains an alternative.

Sources: [Android DualSense layout](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/data/keyboards/Vendor_054c_Product_0ce6.kl), [Linux hid-playstation touchpad mapping](https://github.com/torvalds/linux/blob/master/drivers/hid/hid-playstation.c).

## Picture and covers

The engine exposes a core-lock-protected display aspect getter. The GL surface is centered and fitted to that aspect, making static parent backgrounds visible in the side borders. The 720p render-height cap is retained. No game image is stretched to fill widescreen.

Six choices include black plus five original static designs drawn from primitives. They have no animation, network fetch, bitmap cache, or per-frame invalidation. Simple bilinear uses the engine's existing basic shader; no new heavy effects were added.

Cover lookup streams a bundled metadata-only index of three Libretro Thumbnails repositories. Region tags, punctuation, diacritics, ampersands and leading/trailing “The” are normalized. Exact normalized-title matching preserves sequel numbers; there is no speculative fuzzy match. Box art is preferred; titles/screenshots are available if no box art exists. Up to four candidate requests retain existing download/concurrency limits. Old missing-cover markers use a new version so improved matching takes effect immediately.

Desert Demolition is present as “Desert Demolition Starring Road Runner and Wile E. Coyote (USA, Europe).png”; the previous USA/World-only guesses missed it.

## Language, privacy and packaging

English, Russian, Spanish, Portuguese, French, German and Italian have complete resource sets. English is selected on first run independently of the TV language. AppCompat locales support Android 8–12; Android 13+ uses system per-app locales. Background save messages obtain a localized context. [Android documentation](https://developer.android.com/guide/topics/resources/app-languages).

Internal package/preferences/save identifiers stay stable for compatibility. Only the new standalone preview needs a new package because earlier CI private signing keys are unavailable. A committed public test key makes future preview updates consistent; it must never be used as a production identity.

License notices are included and accessible. The app is for personal noncommercial use. No game content, firmware asset or box art is added to APK assets; the build audits its asset allowlist. Repository visibility is not a substitute for complying with licenses. No repository visibility change is necessary for this personal-use task.

## Device acceptance checks

- Launch each console, move diagonally using the left stick; rotate/click the right stick and verify no pause/rewind/direction changes.
- Click the touchpad (not just touch it). Confirm pause; if unsupported by the TV, verify remote Back. Options must remain Start.
- Hold/release D-pad Left; adjust with left stick; cancel with Circle, then keep holding Left to confirm no repeated entry until release.
- On SNES, check L2/R2 as L/R; hold L1 and L2, release one, verify the other remains effective. Repeat with R1/R2 and reconnect the controller.
- Open every background and a 4:3 game; verify no stretch and black default on clean install. Check sharp and bilinear at 720p.
- Switch each language, reopen app and verify persistence; open save/error/settings dialogs.
- Reimport Desert Demolition, verify cover resolution and offline cached display; disable cover downloads and verify the library still works.
- Repeat the audio and memory protocols in the linked investigation documents.

Automated results are recorded by GitHub Actions; these hardware checks are deliberately not marked completed by CI.
