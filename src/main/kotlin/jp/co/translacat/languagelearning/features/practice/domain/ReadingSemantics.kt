package jp.co.translacat.languagelearning.features.practice.domain

import kotlinx.serialization.json.*

internal data class ReadingVerdict(
    val order: Int,
    val bestAnswerKey: String,
    val ambiguous: Boolean,
    val supported: Boolean,
    val modeFit: Boolean,
    val answerLeakage: Boolean,
    val distractorsPlausible: Boolean,
    val stemPresuppositionsSupported: Boolean,
    val distinctReadingTask: Boolean,
    val readingOperation: String,
    val stemEvidenceSpanIds: List<String>,
    val boundedStructureScope: Boolean? = null,
)

internal data class ReadingEvidenceSpan(val id: String, val passageId: String, val text: String)

/** 기존 Python 판정의 순서와 거부 사유를 그대로 유지한다. */
internal object ReadingSemantics {
    fun rejection(verdict: ReadingVerdict, expectedKey: String, mode: String, skill: String, position: Int): String? =
        when {
            verdict.ambiguous -> "ambiguous single-choice item"
            !verdict.supported -> "answer is not sufficiently supported"
            !verdict.stemPresuppositionsSupported -> "question stem presupposition is not supported by passage"
            !verdict.distinctReadingTask -> if (mode == "STRUCTURE" && position == 1)
                "question consumes later passage structure tasks" else "question repeats a previous reading judgment"

            (skill in setOf("INFERENCE", "CONTEXT_INFERENCE") || mode == "CONTEXT_INFERENCE") &&
                verdict.readingOperation == "DIRECT_RETRIEVAL" -> "inference question only requires direct retrieval"

            skill == "STRUCTURE" && verdict.readingOperation != "DISCOURSE_STRUCTURE" -> "structure question does not require discourse reasoning"
            !verdict.modeFit -> "question does not fit requested mode/skill"
            verdict.answerLeakage -> "semantic verifier detected answer leakage"
            !verdict.distractorsPlausible -> "distractors are too weak or unrelated"
            verdict.bestAnswerKey != expectedKey -> "answer mismatch expected=$expectedKey verifier=${verdict.bestAnswerKey}"
            else -> null
        }

    fun distractorOnly(verdict: ReadingVerdict, expectedKey: String): Boolean =
        verdict.stemPresuppositionsSupported && verdict.distinctReadingTask && verdict.stemEvidenceSpanIds.isNotEmpty() &&
            verdict.bestAnswerKey == expectedKey && !verdict.ambiguous && verdict.supported && verdict.modeFit &&
            !verdict.answerLeakage && !verdict.distractorsPlausible

    fun evidence(questions: List<PracticeQuestionContent>): List<ReadingEvidenceSpan> {
        // 지문 ID가 같은데 본문이 다른 묶음은 검증 요청 전 거부한다.
        val passages = linkedMapOf<String, String>()
        questions.forEach { question ->
            val id = question.passageId
            val text = question.passageText
            if (id.isNullOrBlank() || text.isNullOrBlank() || (passages[id] != null && passages[id] != text)) invalid()
            passages[id] = text
        }
        val spans = passages.flatMap { (id, text) ->
            Regex("[^。！？.!?\\r\\n]+[。！？.!?]?").findAll(text).map { it.value.trim() }.filter(String::isNotEmpty)
                .mapIndexed { index, value -> ReadingEvidenceSpan("$id:s${index + 1}", id, value) }.toList()
        }
        if (spans.isEmpty()) invalid()
        return spans
    }

    fun parse(raw: JsonElement, questions: List<PracticeQuestionContent>, mode: String): List<ReadingVerdict> {
        // schema 구조와 coverage 오류는 문항 품질 거부와 구분해서 처리한다.
        val values = (raw as? JsonObject)?.get("verdicts") as? JsonArray ?: invalid()
        val results = values.map { value ->
            val item = value as? JsonObject ?: invalid()
            fun text(key: String): String {
                val field = item[key] as? JsonPrimitive ?: invalid()
                if (!field.isString) invalid()
                return field.content
            }

            fun flag(key: String, strict: Boolean = false): Boolean {
                val field = item[key] as? JsonPrimitive ?: invalid()
                if (strict) return field.takeUnless { it.isString }?.booleanOrNull ?: invalid()

                // 원본 Pydantic의 일반 bool과 Reading 전용 StrictBool의 수용 범위를 구분한다.
                if (!field.isString && field.doubleOrNull != null) return when (field.doubleOrNull) {
                    1.0 -> true
                    0.0 -> false
                    else -> invalid()
                }
                return when (field.content.lowercase()) {
                    "1", "true", "t", "on", "y", "yes" -> true
                    "0", "false", "f", "off", "n", "no" -> false
                    else -> invalid()
                }
            }

            val order = (item["order"] as? JsonPrimitive)?.intOrNull ?: invalid()
            val spans = (item["stemEvidenceSpanIds"] as? JsonArray)?.map {
                (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: invalid()
            } ?: invalid()
            val operation = text("readingOperation")
            text("reason")
            flag("contextDependent")
            if (operation !in setOf("DIRECT_RETRIEVAL", "INFERENCE", "DISCOURSE_STRUCTURE")) invalid()
            ReadingVerdict(
                order, text("bestAnswerKey"), flag("ambiguous"), flag("supported"), flag("modeFit"),
                flag("answerLeakage"), flag("distractorsPlausible"),
                flag("stemPresuppositionsSupported", strict = true),
                flag("distinctReadingTask", strict = true), operation, spans,
                if (mode == "STRUCTURE") flag("boundedStructureScope", strict = true) else null,
            )
        }
        if (results.size != questions.size || results.map { it.order }.toSet() != questions.map { it.order }
                .toSet()) invalid()

        // 검증기가 선택한 span은 해당 문항의 실제 지문에 속해야 하며 중복·누락을 허용하지 않는다.
        val spanById = evidence(questions).associateBy { it.id }
        val questionByOrder = questions.associateBy { it.order }
        results.forEach { result ->
            val spans = result.stemEvidenceSpanIds
            if (spans.isEmpty() || spans.distinct().size != spans.size ||
                spans.any { spanById[it]?.passageId != questionByOrder.getValue(result.order).passageId }
            ) invalid()
        }
        return results
    }

    private fun invalid(): Nothing = throw PracticeFailure("VERIFIER_SCHEMA_INVALID", 422)
}
