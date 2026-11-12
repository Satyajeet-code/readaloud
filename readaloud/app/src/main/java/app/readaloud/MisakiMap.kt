package app.readaloud

/**
 * Kokoro v1.0 was trained on "misaki" phonemes. We phonemize with espeak-ng
 * (via piper-phonemize) because it handles *any* word, number or name, then
 * rewrite espeak's IPA into misaki's symbols (e.g. "eɪ" -> "A", "dʒ" -> "ʤ").
 *
 * Measured against Kokoro's own pronunciation lexicon this cuts the phoneme
 * mismatch from 31% to 6.6% (US) and from 21% to 5.0% (UK).
 * tools/misaki_map.py is the Python reference; keep the two identical.
 */
enum class Dialect { US, GB, OTHER }

object MisakiMap {
    private val COMMON = listOf(
        "ʔn̩" to "tᵊn", "ʔn" to "tᵊn", "ʔ" to "t",
        "aɪ" to "I", "aʊ" to "W", "eɪ" to "A", "ɔɪ" to "Y",
        "dʒ" to "ʤ", "tʃ" to "ʧ",
        "ɐ" to "ə", "r" to "ɹ", "x" to "k", "ç" to "k", "ɬ" to "l", "ʲ" to "",
    )
    private val US = listOf(
        "ɜːɹ" to "ɜɹ", "ɜː" to "ɜɹ", "oːɹ" to "ɔɹ", "oʊ" to "O", "əʊ" to "O",
        "ɪə" to "iə", "ɚ" to "əɹ",
    ) + COMMON + listOf("ː" to "")
    private val GB = listOf(
        "eə" to "ɛː", "əʊ" to "Q", "oʊ" to "Q", "iə" to "ɪə",
    ) + COMMON
    private val OTHER = listOf(
        "aɪ" to "I", "aʊ" to "W", "dz" to "ʣ", "dʒ" to "ʤ", "eɪ" to "A",
        "oʊ" to "O", "əʊ" to "Q", "ts" to "ʦ", "tʃ" to "ʧ", "ɔɪ" to "Y",
    )

    private const val CONS = "bcdfghjklmnpqstvwxzðθʃʒŋɡɹɾʤʧ"
    private const val BOUNDARY = "\$|[ ,.!?;:—…\"]"

    // Unstressed "əl" / "ən" after a consonant at a word end are syllabic in
    // misaki ("people" -> pipᵊl, "often" -> ɔfᵊn).
    private val SYLLABIC_L = Regex("(?<=[$CONS])əl(?=\$|[ zds,.!?;:—…\"])")
    private val SYLLABIC_N = Regex("(?<=[$CONS])ən(?=$BOUNDARY|[tdsz](?=$BOUNDARY))")

    fun map(espeak: String, dialect: Dialect): String {
        val rules = when (dialect) {
            Dialect.US -> US
            Dialect.GB -> GB
            Dialect.OTHER -> OTHER
        }
        var ps = espeak
        for ((from, to) in rules) ps = ps.replace(from, to)
        if (dialect != Dialect.OTHER) {
            ps = SYLLABIC_L.replace(ps, "ᵊl")
            ps = SYLLABIC_N.replace(ps, "ᵊn")
        }
        return ps
    }
}
