package id.teksvoice.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavReaderTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun wav(
        dataBytes: Int,
        declaredDataSize: Int = dataBytes,
        sampleRate: Int = 22050,
        channels: Int = 1,
        bits: Int = 16
    ): File {
        val bb = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray())
        bb.putInt(36 + dataBytes)
        bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray())
        bb.putInt(16)
        bb.putShort(1)
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * channels * bits / 8)
        bb.putShort((channels * bits / 8).toShort())
        bb.putShort(bits.toShort())
        bb.put("data".toByteArray())
        bb.putInt(declaredDataSize)
        val file = folder.newFile()
        file.writeBytes(bb.array())
        return file
    }

    @Test
    fun readsMonoPcm16() {
        val info = WavReader.read(wav(dataBytes = 1000))
        assertNotNull(info)
        assertEquals(22050, info!!.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(44L, info.dataOffset)
        assertEquals(1000L, info.dataLength)
    }

    @Test
    fun zeroDataSizeUsesRestOfFile() {
        val info = WavReader.read(wav(dataBytes = 640, declaredDataSize = 0))
        assertEquals(640L, info!!.dataLength)
    }

    @Test
    fun rejects8BitAudio() {
        assertNull(WavReader.read(wav(dataBytes = 100, bits = 8)))
    }

    @Test
    fun rejectsNonWavFile() {
        val file = folder.newFile()
        file.writeBytes(ByteArray(100))
        assertNull(WavReader.read(file))
    }
}
