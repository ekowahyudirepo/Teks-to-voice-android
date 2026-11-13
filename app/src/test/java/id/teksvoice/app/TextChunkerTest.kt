package id.teksvoice.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextChunkerTest {

    @Test
    fun emptyTextGivesNoChunks() {
        assertTrue(TextChunker.split("").isEmpty())
        assertTrue(TextChunker.split("  \n \n").isEmpty())
    }

    @Test
    fun eachLineBecomesAChunkWithPause() {
        val chunks = TextChunker.split("Halo dunia\nIni baris dua")
        assertEquals(2, chunks.size)
        assertEquals("Halo dunia", chunks[0].text)
        assertEquals("Ini baris dua", chunks[1].text)
        assertEquals(TextChunker.LINE_PAUSE_MS, chunks[0].pauseAfterMs)
    }

    @Test
    fun blankLineMakesLongerPause() {
        val chunks = TextChunker.split("Paragraf satu\n\nParagraf dua")
        assertEquals(2, chunks.size)
        assertEquals(TextChunker.PARAGRAPH_PAUSE_MS, chunks[0].pauseAfterMs)
    }

    @Test
    fun windowsLineEndingsAreHandled() {
        val chunks = TextChunker.split("Satu\r\nDua\r\n")
        assertEquals(listOf("Satu", "Dua"), chunks.map { it.text })
    }

    @Test
    fun offsetsPointToOriginalText() {
        val source = "  Baris pertama  \n\n   Baris kedua"
        for (chunk in TextChunker.split(source)) {
            assertEquals(chunk.text, source.substring(chunk.start, chunk.end))
        }
    }

    @Test
    fun longLineIsSplitIntoSmallPieces() {
        val source = "kata ".repeat(300).trim()
        val chunks = TextChunker.split(source)
        assertTrue(chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(chunk.text.length <= TextChunker.MAX_CHUNK)
            assertEquals(chunk.text, source.substring(chunk.start, chunk.end))
        }
        assertEquals(300, chunks.sumOf { it.text.split(" ").size })
    }
}
