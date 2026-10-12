package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV3Policy.Segment
import kotlinx.serialization.json.*

/** 요구 분해 경계의 누락 공백만 원문에서 복원한다. 의미 정책과 구형 검증은 그대로 유지한다. */
internal object CuratedWritingFeedbackPolicy {
    const val version = "curated-writing-feedback-v7"

    data class Context(val writingType: WritingType, val public: JsonObject, val originLanguage: String, val learningLanguage: String) {
        private val stored = CuratedWritingFeedbackV6Policy.Context(writingType, public, originLanguage, learningLanguage)
        fun previous() = stored
    }

    fun segments(answer: String): List<Segment> = CuratedWritingFeedbackV6Policy.segments(answer)
    fun requirements(checklist: JsonArray): List<Pair<String, String>> = CuratedWritingFeedbackV6Policy.requirements(checklist)
    fun taskFacts(context: Context): List<Pair<String, String>> = CuratedWritingFeedbackV6Policy.taskFacts(context.previous())
    fun prompt(context: Context, reference: JsonObject, answer: String, requirements: List<Pair<String, String>>, segments: List<Segment>): String =
        CuratedWritingFeedbackV6Policy.prompt(context.previous(), reference, answer, requirements, segments)

    val instructions = CuratedWritingFeedbackV6Policy.instructions
    fun schema(context: Context, requirements: List<Pair<String, String>>, segments: List<Segment>): JsonObject =
        CuratedWritingFeedbackV6Policy.schema(context.previous(), requirements, segments)

    fun validate(output: JsonElement, context: Context, requirements: List<Pair<String, String>>, segments: List<Segment>): JsonObject {
        try {
            // 실제 요구의 순서와 문자를 기준으로 공백만 복원한다. 원래 Provider 응답 객체는 수정하지 않는다.
            val root = output.jsonObject
            val texts = requirements.toMap()
            val observations = root.getValue("observations").jsonArray.map { raw ->
                val row = raw.jsonObject
                val original = texts.getValue(row.getValue("requirementId").jsonPrimitive.content)
                val parts = restoreBoundaryWhitespace(original, row.getValue("requirementParts").jsonArray)
                JsonObject(row + ("requirementParts" to parts))
            }
            val restored = JsonObject(root + ("observations" to JsonArray(observations)))

            // 복원 후에도 v6의 전체 coverage·근거·교정·언어 검사를 모두 통과해야 저장한다.
            val checked = CuratedWritingFeedbackV6Policy.validate(restored, context.previous(), requirements, segments)
            return JsonObject(checked + ("policyVersion" to JsonPrimitive(version)))
        } catch (_: Exception) {
            throw IllegalArgumentException("WRITING_FEEDBACK_SCHEMA_INVALID")
        }
    }

    private fun restoreBoundaryWhitespace(original: String, rawParts: JsonArray): JsonArray {
        require(rawParts.isNotEmpty())
        var cursor = 0
        val parts = rawParts.map { raw ->
            val part = raw.jsonObject
            val quote = part.getValue("requirementQuote").jsonPrimitive.content
            require(quote.isNotBlank())
            val start = original.indexOf(quote, cursor)
            require(start >= cursor)
            val gap = original.substring(cursor, start)
            require(gap.all(Char::isWhitespace))
            cursor = start + quote.length
            JsonObject(part + ("requirementQuote" to JsonPrimitive(gap + quote)))
        }.toMutableList()

        // 끝의 원본 공백도 마지막 부분에 보존한다. 빠진 접속사나 내용은 공백으로 간주하지 않는다.
        val suffix = original.substring(cursor)
        require(suffix.all(Char::isWhitespace))
        val last = parts.last()
        parts[parts.lastIndex] = JsonObject(last + ("requirementQuote" to
            JsonPrimitive(last.getValue("requirementQuote").jsonPrimitive.content + suffix)))
        return JsonArray(parts)
    }
}
