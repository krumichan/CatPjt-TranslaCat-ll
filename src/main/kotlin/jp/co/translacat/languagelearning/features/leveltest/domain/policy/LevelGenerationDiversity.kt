package jp.co.translacat.languagelearning.features.leveltest.domain.policy

import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningText
import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.text.Normalizer
import java.util.*
import kotlin.math.sqrt

internal data class LevelDiversityDecision(val reason: String?, val metadata: JsonObject) {
    val accepted get() = reason == null
}

/** 기존 Python 다양성 정책의 정규화·문자 n-gram·거절 순서를 그대로 유지한다. */
internal class LevelGenerationDiversity(private val context: JsonObject, relaxedHistory: Boolean = false) {
    private val current = entries("currentSession")
    private val same = entries("sameFeatureRecent")
    private val cross = entries("crossFeatureRecent")
    private val sameThreshold = .88 + if (relaxedHistory) .03 else .0
    private val crossThreshold = minOf(.95, .92 + if (relaxedHistory) .03 else .0)
    private val exact =
        context["exactContentHashes90d"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.toMutableSet().apply {
            (current + same + cross).forEach { entry ->
                add(
                    entry.string("contentHash").takeUnless { it.isNullOrEmpty() } ?: hash(
                        entry.string("content").orEmpty(),
                    ),
                )
            }
        }

    fun validate(candidate: JsonObject): LevelDiversityDecision {
        // 서버가 hash와 similarity key를 계산하며 모델이 제공한 값은 채택하지 않는다.
        val content = candidate.getValue("referencePayload").jsonObject.string("sourceText")?.takeIf { it.isNotBlank() }
            ?: candidate.getValue("promptText").jsonPrimitive.content
        val metadata = candidate.getValue("diversityMetadata").jsonObject
        val hash = hash(content)
        val finalized = JsonObject(
            metadata + mapOf(
                "contentHash" to JsonPrimitive(hash),
                "similarityKey" to
                    JsonPrimitive(
                        sha256(normalize(metadata.string("semanticSummary").orEmpty()) + "|" + normalize(content)),
                    ),
            ),
        )

        fun rejected(reason: String) = LevelDiversityDecision(reason, finalized)
        if (metadata["requiresBackgroundKnowledge"]?.jsonPrimitive?.booleanOrNull == true) return rejected(
            "BACKGROUND_KNOWLEDGE",
        )
        if (hash in exact) return rejected("EXACT")

        // 같은 세션의 구조/유사도 기준은 완화하지 않고 과거 이력의 유사도만 원본 한도 안에서 완화한다.
        val signature = signature(metadata)
        for (entry in current) {
            if (signature(entry) == signature) return rejected("STRUCTURAL")
            if (cosine(content, entry.string("content").orEmpty()) >= .82) return rejected("SIMILARITY")
        }
        for (entry in same) {
            if (cosine(content, entry.string("content").orEmpty()) >= sameThreshold) return rejected("SIMILARITY")
            val age = entry["ageDays"]?.jsonPrimitive?.intOrNull
            if (age != null && age <= 7 && signature(entry) == signature &&
                entry["grammarFocusCodes"]?.jsonArray.orEmpty()
                    .toSet()
                    .intersect(metadata["grammarFocusCodes"]?.jsonArray.orEmpty().toSet())
                    .isNotEmpty()
            )
                return rejected("STRUCTURAL")
        }
        for (entry in cross) if (cosine(content, entry.string("content").orEmpty()) >= crossThreshold) return rejected(
            "SIMILARITY",
        )
        return LevelDiversityDecision(null, finalized)
    }

    private fun entries(key: String) = context[key]?.jsonArray.orEmpty().map { it.jsonObject }
    private fun signature(value: JsonObject): List<String>? {
        val scenario = value.string("scenarioCategory") ?: return null
        val intent = value.string("communicativeIntent") ?: return null
        val task = value.string("taskArchetype")?.takeIf { it.isNotEmpty() } ?: return null
        return listOf(scenario, intent, task.trim { pythonSpace(it.code) }.uppercase(Locale.ROOT))
    }

    companion object {
        internal fun normalize(text: String): String {
            val value = ListeningText.casefold(Normalizer.normalize(text, Normalizer.Form.NFKC))
            val normalized = buildString {
                value.codePoints().forEach { code ->
                    val category = Character.getType(code)
                    if (code == '_'.code || Character.isLetter(code) || category in setOf(
                            Character.DECIMAL_DIGIT_NUMBER.toInt(),
                            Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
                        )
                    ) appendCodePoint(code) else append(' ')
                }
            }
            return normalized.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        }

        internal fun hash(text: String) = sha256(normalize(text))
        internal fun cosine(left: String, right: String): Double {
            val a = grams(left)
            val b = grams(right)
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val dot = a.entries.sumOf { (key, count) -> count.toDouble() * (b[key] ?: 0) }
            return dot / (sqrt(a.values.sumOf { it.toDouble() * it }) * sqrt(b.values.sumOf { it.toDouble() * it }))
        }

        private fun grams(text: String): Map<String, Int> {
            val codePoints = normalize(text).replace(" ", "").codePoints().toArray()
            if (codePoints.isEmpty()) return emptyMap()
            if (codePoints.size < 3) return mapOf(String(codePoints, 0, codePoints.size) to 1)
            return (0..codePoints.size - 3).map { String(codePoints, it, 3) }.groupingBy { it }.eachCount()
        }

        private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        private fun pythonSpace(code: Int) = Character.isWhitespace(code) || Character.isSpaceChar(code) || code == 0x85
        private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.contentOrNull
    }
}
