package com.retrotv.emu

/**
 * Поддерживаемые консоли.
 *
 * maxRomBytes — жёсткий предел размера рома. Всё, что больше, — точно не ром
 * этой консоли (или битый файл), импорт будет остановлен. Это одна из защит,
 * чтобы случайно не залить в память ТВ огромный файл.
 */
enum class SystemType(
    val id: String,
    val title: String,
    val extensions: Set<String>,
    val maxRomBytes: Long,
    val coreLibName: String
) {
    NES(
        id = "nes",
        title = "NES / Dendy",
        extensions = setOf("nes"),
        maxRomBytes = 8L * 1024 * 1024,
        coreLibName = "libfceumm.so"
    ),
    SNES(
        id = "snes",
        title = "Super Nintendo",
        extensions = setOf("sfc", "smc"),
        maxRomBytes = 16L * 1024 * 1024,
        coreLibName = "libsnes9x.so"
    ),
    MEGADRIVE(
        id = "md",
        title = "Sega Mega Drive",
        extensions = setOf("md", "gen", "bin", "smd"),
        maxRomBytes = 16L * 1024 * 1024,
        coreLibName = "libgenesis.so"
    );

    companion object {
        /** Абсолютный потолок на любой импортируемый файл (вторая линия защиты). */
        const val HARD_LIMIT_BYTES: Long = 32L * 1024 * 1024

        fun fromFileName(name: String): SystemType? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }

        fun fromId(id: String?): SystemType? = entries.firstOrNull { it.id == id }
    }

    /**
     * Проверка сигнатуры файла (магические байты), где это возможно.
     * NES: заголовок "NES\x1A". Mega Drive (.md/.gen/.bin): строка "SEGA" по смещению 0x100.
     * Для SNES и .smd надёжной сигнатуры нет — пропускаем проверку.
     */
    fun looksLikeValidRom(header: ByteArray, ext: String): Boolean = when (this) {
        NES -> header.size >= 4 &&
                header[0] == 'N'.code.toByte() &&
                header[1] == 'E'.code.toByte() &&
                header[2] == 'S'.code.toByte() &&
                header[3] == 0x1A.toByte()

        MEGADRIVE -> if (ext == "smd") true else {
            header.size >= 0x104 && String(header, 0x100, 4) == "SEGA"
        }

        SNES -> true
    }
}
