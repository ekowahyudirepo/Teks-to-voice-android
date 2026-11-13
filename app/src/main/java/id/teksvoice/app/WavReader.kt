package id.teksvoice.app

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Lokasi data PCM di dalam file WAV. */
data class WavInfo(
    val sampleRate: Int,
    val channels: Int,
    val dataOffset: Long,
    val dataLength: Long
)

object WavReader {

    /** Membaca header WAV PCM 16-bit mono/stereo. Mengembalikan null jika formatnya tidak didukung. */
    fun read(file: File): WavInfo? {
        RandomAccessFile(file, "r").use { f ->
            val length = f.length()
            if (length < 44) return null

            val riff = ByteArray(12)
            f.readFully(riff)
            if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(riff, 8, 4, Charsets.US_ASCII) != "WAVE"
            ) return null

            var sampleRate = 0
            var channels = 0
            var bits = 0
            var format = 0

            val head = ByteArray(8)
            var pos = 12L
            while (pos + 8 <= length) {
                f.seek(pos)
                f.readFully(head)
                val id = String(head, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(head, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                val body = pos + 8

                if (id == "fmt ") {
                    if (size < 16 || body + 16 > length) return null
                    val fmt = ByteArray(16)
                    f.readFully(fmt)
                    val bb = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                    format = bb.short.toInt() and 0xFFFF
                    channels = bb.short.toInt() and 0xFFFF
                    sampleRate = bb.int
                    bb.int // byte rate
                    bb.short // block align
                    bits = bb.short.toInt() and 0xFFFF
                } else if (id == "data") {
                    if (sampleRate <= 0 || channels !in 1..2 || bits != 16) return null
                    if (format != 1 && format != 0xFFFE) return null
                    val available = length - body
                    // Sebagian mesin suara menulis ukuran data 0; pakai sisa file.
                    val dataLength = if (size == 0L || size > available) available else size
                    return WavInfo(sampleRate, channels, body, dataLength)
                }
                pos = body + size + (size and 1L)
            }
            return null
        }
    }
}
