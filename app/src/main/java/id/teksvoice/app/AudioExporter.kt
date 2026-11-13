package id.teksvoice.app

import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Mengubah daftar teks menjadi satu file M4A: tiap bagian disintesis ke WAV sementara oleh mesin suara,
 * lalu digabung (dengan jeda) dan di-encode ke AAC. Dijalankan di thread latar belakang.
 */
class AudioExporter(
    private val engine: TextToSpeech,
    private val cacheDir: File,
    private val restoreListener: UtteranceProgressListener
) {
    class Item(val text: String, val pauseAfterMs: Long)

    @Volatile
    var cancelled = false
        private set

    @Volatile
    private var latch: CountDownLatch? = null

    @Volatile
    private var currentId: String? = null

    @Volatile
    private var failed = false

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {}

        override fun onDone(utteranceId: String?) {
            if (utteranceId == currentId) latch?.countDown()
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) = fail(utteranceId)

        override fun onError(utteranceId: String?, errorCode: Int) = fail(utteranceId)

        override fun onStop(utteranceId: String?, interrupted: Boolean) = fail(utteranceId)

        private fun fail(utteranceId: String?) {
            if (utteranceId == currentId) {
                failed = true
                latch?.countDown()
            }
        }
    }

    fun cancel() {
        cancelled = true
        try {
            engine.stop()
        } catch (e: Exception) {
            // abaikan
        }
        latch?.countDown()
    }

    /** [onProgress] menerima (bagian ke-, total bagian). Melempar [IOException] jika gagal. */
    fun export(items: List<Item>, outFile: File, onProgress: (Int, Int) -> Unit) {
        engine.setOnUtteranceProgressListener(listener)
        val chunkFile = File(cacheDir, "export_chunk.wav")
        var writer: AacM4aWriter? = null
        try {
            for ((i, item) in items.withIndex()) {
                if (cancelled) throw IOException("Dibatalkan")
                onProgress(i + 1, items.size)
                synthesize(item.text, chunkFile, "e$i")

                val wav = WavReader.read(chunkFile)
                    ?: throw IOException("Format audio dari mesin suara tidak didukung")
                val w = writer ?: AacM4aWriter(wav.sampleRate, wav.channels, outFile).also { writer = it }
                if (wav.sampleRate != w.sampleRate || wav.channels != w.channels) {
                    throw IOException("Format audio antar bagian tidak sama")
                }

                streamPcm(chunkFile, wav, w)
                if (item.pauseAfterMs > 0 && i < items.lastIndex) w.writeSilence(item.pauseAfterMs)
            }
            val finalWriter = writer ?: throw IOException("Tidak ada audio yang dihasilkan")
            finalWriter.finish()
        } catch (e: Exception) {
            writer?.release()
            throw e
        } finally {
            chunkFile.delete()
            try {
                engine.setOnUtteranceProgressListener(restoreListener)
            } catch (e: Exception) {
                // mesin suara mungkin sudah ditutup
            }
        }
    }

    private fun synthesize(text: String, file: File, utteranceId: String) {
        failed = false
        val done = CountDownLatch(1)
        latch = done
        currentId = utteranceId
        if (file.exists()) file.delete()

        val result = engine.synthesizeToFile(text, null, file, utteranceId)
        if (result != TextToSpeech.SUCCESS) throw IOException("Mesin suara menolak membuat file audio")
        if (!done.await(120, TimeUnit.SECONDS)) throw IOException("Waktu pembuatan audio habis")
        if (cancelled) throw IOException("Dibatalkan")
        if (failed || !file.exists() || file.length() < 44) {
            throw IOException("Gagal membuat audio untuk salah satu bagian")
        }
    }

    private fun streamPcm(file: File, wav: WavInfo, writer: AacM4aWriter) {
        RandomAccessFile(file, "r").use { f ->
            f.seek(wav.dataOffset)
            var remaining = wav.dataLength
            val buffer = ByteArray(16_384)
            while (remaining > 0) {
                if (cancelled) throw IOException("Dibatalkan")
                val n = f.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (n <= 0) break
                writer.write(buffer, n)
                remaining -= n
            }
        }
    }
}
