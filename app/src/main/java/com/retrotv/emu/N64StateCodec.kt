package com.retrotv.emu

import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Only N64 uses this envelope. Fixed output cap; no growing compression buffer. */
object N64StateCodec {
    const val MAX_RAW = 32 * ResourceBudget.MIB
    const val MAX_PACKED = 8 * ResourceBudget.MIB
    private const val MAGIC = 0x4E363453
    private const val HEADER = 12

    fun encode(raw: ByteArray): ByteArray {
        require(raw.size in 1..MAX_RAW) { "STATE_SIZE" }
        val buffer = ByteArray(minOf(MAX_PACKED.toLong(), raw.size.toLong() + raw.size / 1000 + 128L).toInt())
        ByteBuffer.wrap(buffer).putInt(MAGIC).putInt(raw.size).putInt(CRC32().apply { update(raw) }.value.toInt())
        val deflater = Deflater(Deflater.BEST_SPEED)
        try {
            deflater.setInput(raw); deflater.finish()
            var size = HEADER
            while (!deflater.finished()) {
                check(size < buffer.size) { "STATE_SIZE" }
                val n = deflater.deflate(buffer, size, buffer.size - size)
                check(n > 0) { "STATE_DAMAGED" }; size += n
            }
            return buffer.copyOf(size)
        } finally { deflater.end() }
    }
    fun rawSize(packed: ByteArray): Int {
        require(packed.size in (HEADER + 1)..MAX_PACKED) { "STATE_SIZE" }
        val header = ByteBuffer.wrap(packed)
        require(header.int == MAGIC) { "STATE_DAMAGED" }
        return header.int.also { require(it in 1..MAX_RAW) { "STATE_SIZE" } }
    }
    fun decode(packed: ByteArray): ByteArray {
        val size = rawSize(packed)
        val raw = ByteArray(size)
        val inflater = Inflater()
        try {
            inflater.setInput(packed, HEADER, packed.size - HEADER)
            var offset = 0
            while (offset < size) {
                val n = inflater.inflate(raw, offset, size - offset)
                check(n > 0) { "STATE_DAMAGED" }; offset += n
            }
            check(inflater.finished() && inflater.remaining == 0) { "STATE_DAMAGED" }
            check(CRC32().apply { update(raw) }.value.toInt() == ByteBuffer.wrap(packed).getInt(8)) { "STATE_DAMAGED" }
            return raw
        } finally { inflater.end() }
    }
}
