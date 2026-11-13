package id.teksvoice.app

/**
 * Merapikan teks sebelum dibaca agar terdengar lebih natural dalam Bahasa Indonesia.
 * Murni aturan sederhana (tanpa AI): singkatan umum, angka ribuan, Rp, persen, emoji, dan simbol markdown.
 */
object TextNormalizer {

    private val abbreviations = mapOf(
        "yg" to "yang",
        "dgn" to "dengan",
        "dng" to "dengan",
        "utk" to "untuk",
        "untk" to "untuk",
        "tdk" to "tidak",
        "krn" to "karena",
        "krna" to "karena",
        "sdh" to "sudah",
        "udh" to "sudah",
        "blm" to "belum",
        "jgn" to "jangan",
        "dlm" to "dalam",
        "spt" to "seperti",
        "sbg" to "sebagai",
        "tsb" to "tersebut",
        "dsb" to "dan sebagainya",
        "dst" to "dan seterusnya",
        "dll" to "dan lain-lain",
        "dkk" to "dan kawan-kawan",
        "thn" to "tahun",
        "bln" to "bulan",
        "hrs" to "harus",
        "bgt" to "banget",
        "gmn" to "bagaimana",
        "bgmn" to "bagaimana",
        "emg" to "memang",
        "klo" to "kalau",
        "kalo" to "kalau"
    )

    private val abbreviationRegex =
        Regex("\\b(" + abbreviations.keys.joinToString("|") + ")\\b", RegexOption.IGNORE_CASE)

    private val bulletRegex = Regex("^\\s*[-•·▪●○]+\\s+")
    private val markdownRegex = Regex("[*#_~`|^<>\\[\\]{}]")
    private val thousandsRegex = Regex("(?<![\\d.,])[1-9]\\d{0,2}(?:\\.\\d{3})+(?!\\d)")
    private val rupiahRegex =
        Regex("\\bRp\\.?\\s*(\\d+(?:,\\d+)?)(\\s*(?:ribu|juta|miliar|milyar|triliun))?")
    private val dashAfterNumberRegex = Regex("(\\d),-")
    private val percentRegex = Regex("(\\d)\\s*%")
    private val ampersandRegex = Regex("\\s*&\\s*")
    private val nomorRegex = Regex("\\bNo\\.\\s*(?=\\d)", RegexOption.IGNORE_CASE)
    private val jalanRegex = Regex("\\bJl\\.\\s*")
    private val spacesRegex = Regex("[ \\t]+")
    private val endPunctuationRegex = Regex("[.!?…:;,)\"'”’]$")

    fun normalize(input: String): String {
        var t = removeEmoji(input)
        t = bulletRegex.replace(t, "")
        t = markdownRegex.replace(t, " ")

        // "10.000" -> "10000" supaya dibaca "sepuluh ribu"
        t = thousandsRegex.replace(t) { it.value.replace(".", "") }
        t = rupiahRegex.replace(t) { m -> "${m.groupValues[1]}${m.groupValues[2]} rupiah" }
        t = dashAfterNumberRegex.replace(t, "$1")
        t = percentRegex.replace(t, "$1 persen")
        t = ampersandRegex.replace(t, " dan ")
        t = nomorRegex.replace(t, "nomor ")
        t = jalanRegex.replace(t, "Jalan ")
        t = abbreviationRegex.replace(t) { m -> abbreviations[m.value.lowercase()] ?: m.value }

        t = spacesRegex.replace(t, " ").trim()
        if (t.none { it.isLetterOrDigit() }) return ""
        if (!endPunctuationRegex.containsMatchIn(t)) t += "."
        return t
    }

    private fun removeEmoji(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            if (!isEmojiLike(cp)) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun isEmojiLike(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2B00..0x2BFF ||
            cp == 0xFE0F ||
            cp == 0x200D
}
