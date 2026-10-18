package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV3Policy.Segment
import kotlinx.serialization.json.*

/** 독립 검사의 실제 오류 위치와 필수 문법 교정의 변경 위치를 양방향으로 대조한다. */
internal object CuratedWritingLanguageFormEvidence {
    const val key = "languageFormErrors"
    private val reasons = listOf("SPELLING", "INFLECTION", "SYNTAX")

    fun schema(segments: List<Segment>): JsonObject = buildJsonObject {
        put("type", "array")
        put("items", buildJsonObject {
            put("type", "object")
            put("additionalProperties", false)
            put("properties", buildJsonObject {
                put("segmentId", text(segments.map { it.id }))
                put("quote", text())
                put("errorCore", text())
                put("formReason", text(reasons))
            })
            put("required", JsonArray(listOf("segmentId", "quote", "errorCore", "formReason").map(::JsonPrimitive)))
        })
    }

    fun validate(value: JsonElement, segments: List<Segment>): JsonArray {
        findings(value, segments)
        return value.jsonArray
    }

    fun requireCoverage(corrections: JsonArray, evidence: JsonArray, segments: List<Segment>) {
        require(issues(corrections, evidence, segments).isEmpty()) {
            "WRITING_FEEDBACK_FORM_EVIDENCE_DISAGREEMENT"
        }
    }

    fun issues(corrections: JsonArray, evidence: JsonArray, segments: List<Segment>): List<String> {
        try {
            // 인용 전체가 아니라 독립 검사가 특정한 오류 핵심 위치만 교정 근거로 사용한다.
            val found = findings(evidence, segments)
            val known = segments.associateBy { it.id }
            val changes = corrections.map { it.jsonObject }
                .filter { it.getValue("issueType").jsonPrimitive.content == "LANGUAGE_FORM" }
                .map { row ->
                    val segment = known.getValue(row.getValue("segmentId").jsonPrimitive.content)
                    val patch = row.getValue("patch").jsonObject
                    val original = patch.getValue("original").jsonPrimitive.content
                    val replacement = patch.getValue("replacement").jsonPrimitive.content
                    val offset = uniqueIndex(segment.text, original)
                    require(original != replacement)
                    var prefix = 0
                    while (prefix < minOf(original.length, replacement.length) &&
                        original[prefix] == replacement[prefix]) prefix++
                    var suffix = 0
                    while (suffix < minOf(original.length - prefix, replacement.length - prefix) &&
                        original[original.lastIndex - suffix] == replacement[replacement.lastIndex - suffix]) suffix++
                    Change(segment.id, row.getValue("formReason").jsonPrimitive.content,
                        offset + prefix, offset + original.length - suffix)
                }

            fun supports(change: Change, finding: Finding): Boolean {
                if (change.segmentId != finding.segmentId || change.reason != finding.reason) return false
                return if (change.start == change.end) change.start in finding.start..finding.end
                else change.start < finding.end && finding.start < change.end
            }

            // 근거 없는 교정을 거절하고, 발견한 실제 오류의 교정을 삭제해 통과하는 것도 거절한다.
            val issues = mutableListOf<String>()
            changes.forEachIndexed { index, change ->
                if (found.none { supports(change, it) }) {
                    issues += "LANGUAGE_FORM correction ${index + 1} in ${change.segmentId} lacks blind " +
                        "${change.reason} evidence at its actual changed location; re-check whether the original " +
                        "is grammatical rather than a tense/style preference"
                }
            }
            found.forEachIndexed { index, finding ->
                if (changes.none { supports(it, finding) }) {
                    issues += "blind language-form finding ${index + 1} in ${finding.segmentId} " +
                        "(${finding.reason}) has no correction at its errorCore; do not drop an actual necessary correction"
                }
            }
            return issues
        } catch (_: Exception) {
            return listOf("language-form evidence or local patch cannot be compared because required source fields are invalid")
        }
    }

    private fun findings(value: JsonElement, segments: List<Segment>): List<Finding> {
        try {
            val known = segments.associateBy { it.id }
            val rows = value.jsonArray
            require(rows.size <= 16)
            val result = rows.map { raw ->
                val row = raw.jsonObject
                require(row.keys == setOf("segmentId", "quote", "errorCore", "formReason"))
                val segment = known.getValue(row.getValue("segmentId").jsonPrimitive.content)
                val quote = row.getValue("quote").jsonPrimitive.content
                val core = row.getValue("errorCore").jsonPrimitive.content
                val reason = row.getValue("formReason").jsonPrimitive.content
                require(quote.length <= 160 && core.length <= 32 && reason in reasons)
                val start = uniqueIndex(segment.text, quote) + uniqueIndex(quote, core)
                Finding(segment.id, reason, start, start + core.length)
            }
            require(result.map { Triple(it.segmentId, it.start, it.end) }.distinct().size == result.size)
            return result
        } catch (_: Exception) {
            throw IllegalArgumentException("WRITING_FEEDBACK_FORM_EVIDENCE_INVALID")
        }
    }

    private fun uniqueIndex(text: String, fragment: String): Int {
        require(fragment.isNotBlank())
        val start = text.indexOf(fragment)
        require(start >= 0 && text.indexOf(fragment, start + 1) < 0)
        return start
    }

    private fun text(values: List<String>? = null) = buildJsonObject {
        put("type", "string")
        values?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
    }

    private data class Finding(val segmentId: String, val reason: String, val start: Int, val end: Int)
    private data class Change(val segmentId: String, val reason: String, val start: Int, val end: Int)
}
