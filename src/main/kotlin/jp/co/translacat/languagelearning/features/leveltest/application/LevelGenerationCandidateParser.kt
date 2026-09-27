package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelGenerationAssets

import kotlinx.serialization.json.*

internal data class LevelParsedCandidates(
    val candidates: List<Pair<Int, JsonObject>>, val reasons: List<String>, val rejected: Int,
)

/** 후보를 독립적으로 파싱하여 잘못된 형제 후보가 정상 후보를 폐기하지 않게 한다. */
internal object LevelGenerationCandidateParser {
    fun parse(raw: JsonObject): JsonObject {
        val candidate = LevelPydanticSchema.decode(raw, LevelGenerationAssets.baseSchema("candidate"))
        val mode = candidate.getValue("answerMode").jsonPrimitive.content
        val options = candidate.getValue("options").jsonArray.map { it.jsonObject }
        val answer = candidate.getValue("internalAnswerKey").jsonObject
        fun invalid(code: String): Nothing = throw LevelSchemaFailure(listOf(LevelSchemaIssue("", code)))

        // Pydantic의 model_validator와 같은 순서로 답 모드·선택지·정답의 교차 필드를 검사한다.
        if (mode == "CHOICE") {
            val keys = options.map { it.getValue("key").jsonPrimitive.content }
            if (candidate["itemType"] == JsonPrimitive("GRAMMAR_SENTENCE_ORDER")) {
                if (options.size < 2) invalid("sentence_order_too_few_options")
                if (keys.size != keys.distinct().size) invalid("sentence_order_duplicate_option_key")
                if (answer.getValue("correctOrder").jsonArray.map { it.jsonPrimitive.content }
                        .sorted() != keys.sorted())
                    invalid("sentence_order_correct_order_mismatch")
            } else {
                if (options.size != 4) invalid("choice_option_count_invalid")
                if (answer["correctOptionKey"]?.jsonPrimitive?.contentOrNull !in keys) invalid(
                    "choice_correct_option_missing",
                )
            }
        } else if (options.isNotEmpty()) invalid("non_choice_has_options")
        if (mode in setOf("TEXT", "AUDIO") && candidate["answerLanguage"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty())
            invalid(if (mode == "TEXT") "text_answer_language_missing" else "audio_answer_language_missing")
        return candidate
    }

    fun batch(data: JsonObject): LevelParsedCandidates {
        val candidates = data["candidates"] as? JsonArray ?: return LevelParsedCandidates(
            emptyList(), listOf("SCHEMA:candidates:list_type"), 1,
        )
        if (candidates.isEmpty()) return LevelParsedCandidates(emptyList(), listOf("SCHEMA:candidates:too_short"), 1)
        val parsed = mutableListOf<Pair<Int, JsonObject>>()
        val reasons = mutableListOf<String>()
        var rejected = 0

        // 기존 6개 상한과 후보별 최대 6개 오류, 응답별 최대 12개 진단을 유지한다.
        candidates.take(6).forEachIndexed { index, value ->
            if (value !is JsonObject) {
                rejected++
                reasons += "SCHEMA:candidates.$index:dict_type"
            } else try {
                parsed += index to parse(value)
            } catch (failure: LevelSchemaFailure) {
                rejected++
                reasons += failure.issues.take(6).map {
                    "SCHEMA:candidates.$index.${it.path.ifEmpty { "candidate" }}:${it.code}"
                }
            }
        }
        if (candidates.size > 6) {
            rejected += candidates.size - 6; reasons += "SCHEMA:candidates:too_long"
        }
        return LevelParsedCandidates(parsed, reasons.take(12), rejected)
    }
}
