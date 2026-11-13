package id.teksvoice.app

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException

/**
 * Menulis PCM 16-bit menjadi file M4A (AAC-LC) memakai encoder bawaan Android.
 * Panggil [write] / [writeSilence] berulang, lalu [finish]. Jika terjadi error, panggil [release].
 */
class AacM4aWriter(val sampleRate: Int, val channels: Int, outFile: File) {

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private val frameSize = 2 * channels
    private var trackIndex = -1
    private var muxerStarted = false
    private var totalFrames = 0L
    private var released = false

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, if (channels == 1) 64_000 else 128_000)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)

        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()

        val createdMuxer = try {
            MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            codec.release()
            throw e
        }
        muxer = createdMuxer
    }

    /** [pcm] berisi sampel 16-bit little-endian (interleaved jika stereo). */
    fun write(pcm: ByteArray, length: Int) {
        var offset = 0
        while (offset < length) {
            val index = codec.dequeueInputBuffer(TIMEOUT_US)
            if (index >= 0) {
                val input = codec.getInputBuffer(index) ?: throw IOException("Buffer encoder tidak tersedia")
                input.clear()
                var n = minOf(input.remaining(), length - offset)
                n -= n % frameSize
                if (n <= 0) {
                    codec.queueInputBuffer(index, 0, 0, ptsUs(), 0)
                    break
                }
                input.put(pcm, offset, n)
                codec.queueInputBuffer(index, 0, n, ptsUs(), 0)
                totalFrames += n / frameSize
                offset += n
            }
            drain(false)
        }
    }

    fun writeSilence(durationMs: Long) {
        var remaining = (sampleRate.toLong() * durationMs / 1000) * frameSize
        val zeros = ByteArray(8192)
        while (remaining > 0) {
            val n = minOf(zeros.size.toLong(), remaining).toInt()
            write(zeros, n)
            remaining -= n
        }
    }

    fun finish() {
        try {
            var queued = false
            var tries = 0
            while (!queued) {
                val index = codec.dequeueInputBuffer(TIMEOUT_US)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, ptsUs(), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    queued = true
                } else {
                    drain(false)
                    if (++tries > MAX_IDLE_ROUNDS) throw IOException("Encoder audio tidak merespons")
                }
            }
            drain(true)
            if (!muxerStarted) throw IOException("Tidak ada audio yang dihasilkan")
            muxer.stop()
            muxerStarted = false
        } finally {
            release()
        }
    }

    fun release() {
        if (released) return
        released = true
        try {
            codec.stop()
        } catch (e: Exception) {
            // abaikan
        }
        try {
            codec.release()
        } catch (e: Exception) {
            // abaikan
        }
        if (muxerStarted) {
            try {
                muxer.stop()
            } catch (e: Exception) {
                // abaikan
            }
        }
        try {
            muxer.release()
        } catch (e: Exception) {
            // abaikan
        }
    }

    private fun ptsUs(): Long = totalFrames * 1_000_000L / sampleRate

    private fun drain(endOfStream: Boolean) {
        var idleRounds = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++idleRounds > MAX_IDLE_ROUNDS) throw IOException("Encoder audio tidak merespons")
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    idleRounds = 0
                    val output = codec.getOutputBuffer(index) ?: throw IOException("Buffer encoder tidak tersedia")
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && muxerStarted) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, output, info)
                    }
                    val endReached = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                    if (endReached) return
                }
            }
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
        const val MAX_IDLE_ROUNDS = 500
    }
}
