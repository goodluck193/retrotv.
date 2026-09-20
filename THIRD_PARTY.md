# Сторонние компоненты

- [LibretroDroid](https://github.com/Swordfish90/LibretroDroid/tree/8835c3098514390a271e36983957f7bb5f40abf1), Filippo Scognamiglio, GPL-3.0-or-later. Изменения RetroTV: `native/`, `engine/libretrodroid.patch`.
- [Oboe](https://github.com/google/oboe/tree/b15f5e39c01a7ada306d959e5129620b145fb8b4), Apache-2.0, submodule движка.
- [FCEUmm](https://github.com/libretro/libretro-fceumm/tree/236ccdfc911e84c60fea6b9d0699c2d440a8de14), лицензии и уведомления в исходниках.
- [Snes9x](https://github.com/libretro/snes9x/tree/890b5d445538fe790aa3add3d5702c80f551e0ae), Snes9x License.
- [Genesis Plus GX](https://github.com/libretro/Genesis-Plus-GX/tree/c2838c7dc4236fc2fe94e5dbd08b41486067918e), лицензия проекта и уведомления его компонентов.
- [libretro-thumbnails](https://github.com/libretro-thumbnails), необязательные обложки; права принадлежат соответствующим правообладателям.
- AndroidX, Kotlin, kotlinx.coroutines — зависимости Gradle с собственными лицензиями.

Точные исходники ядер закреплены в `engine/cores.lock.json`; скрипты позволяют
повторить сборку. Тексты лицензий включены в `app/src/main/assets/licenses`;
лицензии ядер дополнительно копируются сборочным скриптом из исходников.
Лицензии компонентов не заменяют друг друга.
