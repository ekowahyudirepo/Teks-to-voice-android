package id.teksvoice.app

import org.junit.Assert.assertEquals
import org.junit.Test

class TextNormalizerTest {

    @Test
    fun expandsCommonAbbreviations() {
        assertEquals("Ini yang penting.", TextNormalizer.normalize("Ini yg penting"))
    }

    @Test
    fun readsRupiahAmounts() {
        assertEquals("Harganya 10000 rupiah saja.", TextNormalizer.normalize("Harganya Rp10.000 saja"))
        assertEquals("5 juta rupiah.", TextNormalizer.normalize("Rp. 5 juta"))
    }

    @Test
    fun readsPercentAndAmpersand() {
        assertEquals("Diskon 50 persen.", TextNormalizer.normalize("Diskon 50%"))
        assertEquals("A dan B.", TextNormalizer.normalize("A & B"))
    }

    @Test
    fun removesEmojiAndMarkdown() {
        assertEquals("Halo dunia.", TextNormalizer.normalize("Halo 😀 dunia"))
        assertEquals("Penting.", TextNormalizer.normalize("**Penting**"))
        assertEquals("Satu item.", TextNormalizer.normalize("- Satu item"))
    }

    @Test
    fun symbolsOnlyBecomeEmpty() {
        assertEquals("", TextNormalizer.normalize("***"))
        assertEquals("", TextNormalizer.normalize("😀😀"))
    }

    @Test
    fun keepsExistingPunctuation() {
        assertEquals("Apa kabar?", TextNormalizer.normalize("Apa kabar?"))
    }
}
