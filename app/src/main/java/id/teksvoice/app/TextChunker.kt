package id.teksvoice.app

/**
 * Satu potongan teks yang dibaca sekali jalan.
 * [start] dan [end] adalah posisi di teks asli (dipakai untuk menyorot bagian yang sedang dibaca).
 */
data class SpeechChunk(
    val text: String,
    val start: Int,
    val end: Int,
    val pauseAfterMs: Long
)

/**
 * Memecah teks multiline menjadi potongan-potongan bacaan.
 * - Setiap baris menjadi satu potongan (baris yang sangat panjang dipecah di akhir kalimat/koma/spasi).
 * - Setelah setiap baris ada jeda singkat, setelah baris kosong (paragraf baru) jeda lebih lama.
 */
object TextChunker {
    const val MAX_CHUNK = 400
    const val LINE_PAUSE_MS = 450L
    const val PARAGRAPH_PAUSE_MS = 900L

    private const val MIN_PIECE = 120

    fun split(source: String): List<SpeechChunk> {
        val result = mutableListOf<SpeechChunk>()
        val n = source.length
        var lineStart = 0
        while (lineStart <= n) {
            var lineEnd = source.indexOf('\n', lineStart)
            if (lineEnd == -1) lineEnd = n

            val sizeBefore = result.size
            addLine(result, source, lineStart, lineEnd)
            val lineWasBlank = result.size == sizeBefore

            if (lineWasBlank && result.isNotEmpty()) {
                val last = result.last()
                if (last.pauseAfterMs < PARAGRAPH_PAUSE_MS) {
                    result[result.lastIndex] = last.copy(pauseAfterMs = PARAGRAPH_PAUSE_MS)
                }
            }
            lineStart = lineEnd + 1
        }
        return result
    }

    private fun addLine(out: MutableList<SpeechChunk>, s: String, from: Int, to: Int) {
        var start = from
        var end = to
        while (start < end && s[start].isWhitespace()) start++
        while (end > start && s[end - 1].isWhitespace()) end--
        if (start >= end) return

        var pos = start
        while (pos < end) {
            val cut = if (end - pos <= MAX_CHUNK) end else findCut(s, pos, pos + MAX_CHUNK)

            var pieceStart = pos
            var pieceEnd = cut
            while (pieceStart < pieceEnd && s[pieceStart].isWhitespace()) pieceStart++
            while (pieceEnd > pieceStart && s[pieceEnd - 1].isWhitespace()) pieceEnd--
            if (pieceEnd > pieceStart) {
                out.add(SpeechChunk(s.substring(pieceStart, pieceEnd), pieceStart, pieceEnd, 0L))
            }
            pos = cut
        }
        out[out.lastIndex] = out.last().copy(pauseAfterMs = LINE_PAUSE_MS)
    }

    /** Cari titik potong terbaik sebelum [end]: akhir kalimat, lalu tanda koma/titik koma, lalu spasi. */
    private fun findCut(s: String, from: Int, end: Int): Int {
        val floor = from + MIN_PIECE
        for (marks in arrayOf(".!?…", ",;:")) {
            var i = end - 1
            while (i > floor) {
                if (s[i] in marks && s[i + 1].isWhitespace()) return i + 1
                i--
            }
        }
        var i = end - 1
        while (i > floor) {
            if (s[i].isWhitespace()) return i
            i--
        }
        return end
    }
}
