package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.text.Normalizer
import java.util.*
import kotlin.math.sqrt

internal data class WritingDiversityDecision(
    val accepted: Boolean,
    val reason: String?,
    val metadata: JsonObject,
)

/** Current Python DiversityValidator policy, including its history-only relaxed fallback. */
internal class WritingDiversityValidator(
    private val context: JsonObject = JsonObject(emptyMap()),
    private val relaxedHistory: Boolean = false,
) {
    private val sameBatchThreshold = 0.82
    private val sameFeatureThreshold = if (relaxedHistory) 0.91 else 0.88
    private val crossFeatureThreshold = if (relaxedHistory) 0.95 else 0.92

    private fun entries(name: String): List<JsonObject> =
        (context[name] as? JsonArray).orEmpty().map(JsonElement::jsonObject)

    private val currentSession = entries("currentSession")
    private val sameFeatureRecent = entries("sameFeatureRecent")
    private val crossFeatureRecent = entries("crossFeatureRecent")
    private val exactHashes: Set<String> = buildSet {
        addAll((context["exactContentHashes90d"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content })
        for (entry in currentSession + sameFeatureRecent + crossFeatureRecent) {
            add(
                entry.stringOrNull("contentHash")?.takeIf(String::isNotEmpty)
                    ?: hash(entry.getValue("content").jsonPrimitive.content),
            )
        }
    }

    fun validate(
        content: String,
        metadata: JsonObject,
        accepted: List<Pair<String, JsonObject>> = emptyList(),
    ): WritingDiversityDecision {
        val contentHash = hash(content)
        val finalized = JsonObject(
            metadata + mapOf(
                "contentHash" to JsonPrimitive(contentHash),
                "similarityKey" to JsonPrimitive(
                    hashMaterial(
                        "${normalize(metadata.getValue("semanticSummary").jsonPrimitive.content)}|${
                            normalize(
                                content,
                            )
                        }",
                    ),
                ),
            ),
        )

        fun reject(reason: String) = WritingDiversityDecision(false, reason, finalized)
        if (finalized.getValue("requiresBackgroundKnowledge").jsonPrimitive.boolean) return reject(
            "BACKGROUND_KNOWLEDGE",
        )
        if (contentHash in exactHashes) return reject("EXACT")

        val signature = signature(finalized)
        for ((otherContent, otherMetadata) in accepted) {
            if (signature(otherMetadata) == signature) return reject("STRUCTURAL")
            if (cosine(content, otherContent) >= sameBatchThreshold) return reject("SIMILARITY")
        }
        for (entry in currentSession) {
            if (signature(entry) == signature) return reject("STRUCTURAL")
            if (cosine(content, entry.getValue("content").jsonPrimitive.content) >= sameBatchThreshold)
                return reject("SIMILARITY")
        }
        val grammarCodes = strings(finalized, "grammarFocusCodes").toSet()
        for (entry in sameFeatureRecent) {
            if (cosine(content, entry.getValue("content").jsonPrimitive.content) >= sameFeatureThreshold)
                return reject("SIMILARITY")
            val age = (entry["ageDays"] as? JsonPrimitive)?.intOrNull
            if (age != null && age <= 7 && signature(entry) == signature &&
                strings(entry, "grammarFocusCodes").any(grammarCodes::contains)
            ) return reject("STRUCTURAL")
        }
        for (entry in crossFeatureRecent) {
            if (cosine(content, entry.getValue("content").jsonPrimitive.content) >= crossFeatureThreshold)
                return reject("SIMILARITY")
        }
        return WritingDiversityDecision(true, null, finalized)
    }

    private fun signature(metadata: JsonObject): Triple<String, String, String>? {
        val scenario = metadata.stringOrNull("scenarioCategory") ?: return null
        val intent = metadata.stringOrNull("communicativeIntent") ?: return null
        val archetype = metadata.stringOrNull("taskArchetype")?.takeIf(String::isNotEmpty) ?: return null
        return Triple(scenario, intent, archetype.trim().uppercase(Locale.ROOT))
    }

    private fun strings(value: JsonObject, field: String): List<String> =
        (value[field] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }

    private fun JsonObject.stringOrNull(field: String): String? =
        (this[field] as? JsonPrimitive)?.contentOrNull

    internal companion object {
        private val punctuation = Regex("[^\\p{L}\\p{N}_\\s]")
        private val whitespace = Regex("\\s+")

        fun normalize(text: String): String = whitespace.replace(
            punctuation.replace(
                Normalizer.normalize(text, Normalizer.Form.NFKC)
                    .lowercase(Locale.ROOT).replace("ß", "ss"),
                " ",
            ),
            " ",
        ).trim()

        private fun hashMaterial(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun hash(text: String) = hashMaterial(normalize(text))

        fun cosine(left: String, right: String): Double {
            fun grams(text: String): Map<String, Int> {
                val compact = normalize(text).replace(" ", "")
                if (compact.isEmpty()) return emptyMap()
                if (compact.length < 3) return mapOf(compact to 1)
                return (0..compact.length - 3).map { compact.substring(it, it + 3) }
                    .groupingBy { it }.eachCount()
            }

            val a = grams(left)
            val b = grams(right)
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val dot = a.entries.sumOf { (gram, count) -> count * (b[gram] ?: 0) }.toDouble()
            val leftNorm = sqrt(a.values.sumOf { it * it }.toDouble())
            val rightNorm = sqrt(b.values.sumOf { it * it }.toDouble())
            return dot / (leftNorm * rightNorm)
        }
    }
}
