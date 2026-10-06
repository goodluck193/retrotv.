# Third-party components and artwork

This is a personal, noncommercial emulator frontend. Component licenses apply independently; none grants rights to commercial game ROMs or box art.

| Component | Pinned source | License / notes |
| --- | --- | --- |
| LibretroDroid, Filippo Scognamiglio | [8835c30](https://github.com/Swordfish90/LibretroDroid/tree/8835c3098514390a271e36983957f7bb5f40abf1) | GPL-3.0-or-later. Our modifications are in `native/` and `engine/libretrodroid.patch`. |
| Oboe | [b15f5e3](https://github.com/google/oboe/tree/b15f5e39c01a7ada306d959e5129620b145fb8b4) | Apache-2.0; engine submodule. |
| FCEUmm | [236ccdf](https://github.com/libretro/libretro-fceumm/tree/236ccdfc911e84c60fea6b9d0699c2d440a8de14) | GPL-2.0-or-later and component notices. The top-level `Copying` file is included, regardless of case. |
| Snes9x | [890b5d4](https://github.com/libretro/snes9x/tree/890b5d445538fe790aa3add3d5702c80f551e0ae) | Snes9x License; personal / noncommercial conditions. [License](https://github.com/libretro/snes9x/blob/890b5d445538fe790aa3add3d5702c80f551e0ae/LICENSE). |
| Genesis Plus GX | [c2838c7](https://github.com/libretro/Genesis-Plus-GX/tree/c2838c7dc4236fc2fe94e5dbd08b41486067918e) | Project license prohibits sale and use in a commercial product or activity. [License](https://github.com/libretro/Genesis-Plus-GX/blob/c2838c7dc4236fc2fe94e5dbd08b41486067918e/LICENSE.txt). |
| AndroidX, Kotlin, kotlinx.coroutines | Versions in Gradle build files | Apache-2.0, plus upstream notices. |
| Libretro Thumbnails | [Repositories](https://github.com/libretro-thumbnails); metadata snapshots in `engine/cover-sources.json` | Optional remote artwork. Images belong to their respective rightsholders; emulator code licenses do not license that artwork. |

Full license texts are packaged under `assets/licenses`, copied from pinned core sources by `scripts/build_cores.py`, and viewable in the app. Sources, revisions, patches and build instructions are available in this repository and the linked upstream repositories. Preserve corresponding-source access and other license obligations when distributing binaries; private use does not require making your own modifications public.

The application does not bundle games, standalone game ROMs, commercial cover images or downloadable game catalogs. Its cover index contains filenames, not images or game data. `scripts/audit_apk.py` rejects unexpected assets and game/BIOS file extensions and checks native library names and core notices. This is a packaging audit, not a forensic proof about every byte of a third-party core or a legal clearance of all upstream material.

The new icon and five static backgrounds were created for this project. They contain no console trademarks or game characters. Nintendo, Super Nintendo, Sega and DualSense names identify compatibility; no endorsement or affiliation is claimed. The app name Retro Console is descriptive and already appears in other app names; no exclusivity or registered-trademark clearance is claimed.

Personal, noncommercial use is permitted by the cited core licenses subject to their conditions. Making a repository private does not remove licensing requirements. Commercial release, monetization, ROM distribution, artwork redistribution or a public branded product requires a separate rights review. This document is a technical inventory, not a promise that litigation is impossible.
