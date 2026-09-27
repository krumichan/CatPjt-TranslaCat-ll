package jp.co.translacat.languagelearning.features.listening.domain.policy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer
import java.util.*

internal data class ListeningNormalizedText(val text: String, val tokens: List<String>)
internal data class ListeningCanonicalAnswer(val text: String, val acceptedTokens: Set<String>)

/** Python listening-normalization의 NFKC·casefold·locale token 규칙을 그대로 사용한다. */
internal object ListeningText {
    private val punctuation = Regex("[^\\p{L}\\p{N}_\\s'’-]", RegexOption.UNIX_LINES)
    private val whitespace = Regex("\\s+", RegexOption.UNIX_LINES)
    private val latin = Regex("[\\p{L}\\p{N}_]+(?:'[\\p{L}\\p{N}_]+)?")
    private val japanese = Regex("[一-龯々〆ヵヶ]|[ぁ-んァ-ンー]|[A-Za-z]+|[0-9]+")
    private val folds: Map<String, String> by lazy {
        val content = checkNotNull(javaClass.getResourceAsStream("/listening/casefold.json"))
            .bufferedReader().use { it.readText() }
        Json.parseToJsonElement(content).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }
    private val contractions = linkedMapOf(
        "can't" to "cannot", "won't" to "will not", "isn't" to "is not", "aren't" to "are not",
        "didn't" to "did not", "doesn't" to "does not", "don't" to "do not", "i'm" to "i am",
        "it's" to "it is", "that's" to "that is",
    )

    fun normalize(text: String, locale: String): ListeningNormalizedText {
        // Python의 문자열 단위 casefold 차이는 추출한 Unicode 표로 보존한다.
        val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        var value = casefold(normalized).replace('’', '\'').replace('‐', '-').replace('–', '-')
        value = whitespace.replace(punctuation.replace(value, " "), " ").trim()
        val language = locale.lowercase(Locale.ROOT).substringBefore('-')
        if (language == "en") {
            contractions.forEach { (contraction, expansion) ->
                value = Regex("\\b${Regex.escape(contraction)}\\b").replace(value, expansion)
            }
            value = whitespace.replace(value, " ").trim()
        }

        // 일본어·중국어는 기존 단문 문자 token을 연결하고 다른 언어는 공백으로 연결한다.
        val tokens = tokenize(value, locale)
        return ListeningNormalizedText(tokens.joinToString(if (language in setOf("ja", "zh")) "" else " "), tokens)
    }

    fun casefold(text: String): String = text.codePoints().toArray().joinToString("") { code ->
        val character = String(Character.toChars(code))
        folds[character] ?: character.lowercase(Locale.ROOT)
    }

    fun tokenize(text: String, locale: String): List<String> {
        val pattern = if (locale.lowercase(Locale.ROOT).substringBefore('-') in setOf("ja", "zh")) japanese else latin
        return pattern.findAll(text).map { it.value }.toList()
    }

    fun canonicalize(
        answer: ListeningNormalizedText, variants: Map<String, List<String>>, locale: String,
    ): ListeningCanonicalAnswer {
        // 긴 허용 표현을 먼저 치환하는 기존 규칙과 accepted token 표시를 함께 보존한다.
        val replacements = mutableListOf<Pair<String, String>>()
        val accepted = mutableSetOf<String>()
        variants.forEach { (source, values) ->
            val canonical = normalize(source, locale)
            if (canonical.text.isNotEmpty()) {
                values.forEach { variant ->
                    val normalized = normalize(variant, locale).text
                    if (normalized.isNotEmpty()) {
                        replacements += normalized to canonical.text
                        accepted += canonical.tokens
                    }
                }
            }
        }
        var value = answer.text
        replacements.sortedByDescending { it.first.length }.forEach { (variant, canonical) ->
            value = value.replace(variant, canonical)
        }
        return ListeningCanonicalAnswer(value, accepted)
    }
}
