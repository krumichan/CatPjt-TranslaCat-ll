package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestItemType
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*

internal class LevelSpeakingProtocolFailure : RuntimeException("LEVEL_TEST_SPEAKING_SCHEMA_INVALID")
internal data class LevelSpeakingEvaluation(val payload: JsonObject, val score: Int?)

/** Python에 이미 있던 형식 정규화와 교차 필드 검사를 이전한다. 점수나 채택 기준을 새로 보완하지 않는다. */
internal object LevelSpeakingEvaluationPolicy {
    val schema: JsonObject by lazy {
        Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/leveltest/speaking-evaluation-schema.json"))
                .readText(),
        ).jsonObject
    }
    val instructions: String by lazy {
        checkNotNull(javaClass.getResource("/leveltest/speaking-evaluation-system-prompt.txt"))
            .readText().replace("\r\n", "\n")
    }
    private val types = setOf("PRONUNCIATION", "FLUENCY", "GRAMMAR", "VOCABULARY", "TASK_FULFILLMENT")
    private val nonResponse = setOf("META_REFUSAL", "OFF_TOPIC", "EMPTY_CONTENT")

    fun parse(raw: JsonElement, itemType: LevelTestItemType, originLanguage: String): LevelSpeakingEvaluation {
        try {
            // 정규화 후 원본 Schema와 metric 상태·과제 비응답의 교차 조건을 모두 검사한다.
            val canonical = aliases(normalize(raw as? JsonObject ?: invalid(), itemType, originLanguage))
            val payload = LevelPydanticSchema.decode(canonical, schema)
            val metrics = payload.getValue("metrics").jsonArray.map { it.jsonObject }
            val actualTypes = metrics.map { it.getValue("type").jsonPrimitive.content }
            if (actualTypes.toSet() != types || actualTypes.distinct().size != actualTypes.size) invalid()
            metrics.forEach { metric ->
                val evaluated = metric.getValue("state").jsonPrimitive.content == "EVALUATED"
                val score = metric["score"]
                if (evaluated && (score == null || score == JsonNull)) invalid()
                if (!evaluated && (score != JsonNull || metric["notEvaluableReason"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty())) invalid()
            }
            val status = payload.getValue("taskResponseStatus").jsonPrimitive.content
            val task = metrics.single { it.getValue("type").jsonPrimitive.content == "TASK_FULFILLMENT" }
            if (status in nonResponse && task.getValue("state").jsonPrimitive.content == "EVALUATED" &&
                task.getValue("score").jsonPrimitive.double != 0.0
            ) invalid()

            // 반복 발화와 유도 발화의 기존 가중치·비응답 상한을 Decimal 반올림으로 보존한다.
            val weights = if (itemType == LevelTestItemType.SPEAKING_REPEAT)
                mapOf("PRONUNCIATION" to "0.60", "FLUENCY" to "0.40") else
                mapOf(
                    "PRONUNCIATION" to "0.20", "FLUENCY" to "0.20", "GRAMMAR" to "0.20",
                    "VOCABULARY" to "0.15", "TASK_FULFILLMENT" to "0.25",
                )
            var weighted = BigDecimal.ZERO
            var available = BigDecimal.ZERO
            for (metric in metrics) {
                val weight = weights[metric.getValue("type").jsonPrimitive.content]?.toBigDecimal() ?: continue
                if (metric.getValue("state").jsonPrimitive.content != "EVALUATED") continue
                weighted += metric.getValue("score").jsonPrimitive.content.toBigDecimal() * weight
                available += weight
            }
            val score = if (available == BigDecimal.ZERO) null else
                weighted.divide(available, 0, RoundingMode.HALF_UP)
                    .toInt()
                    .let { if (status in nonResponse) minOf(it, 10) else it }
            return LevelSpeakingEvaluation(payload, score)
        } catch (_: LevelSchemaFailure) {
            invalid()
        } catch (_: IllegalArgumentException) {
            invalid()
        }
    }

    private fun normalize(raw: JsonObject, itemType: LevelTestItemType, originLanguage: String): JsonObject {
        fun numeric(value: JsonElement?): Double? = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
        fun confidence(value: JsonElement?): JsonElement? = numeric(value)?.let {
            when {
                it > 1 && it <= 5 -> JsonPrimitive(it / 5); it > 5 && it <= 100 -> JsonPrimitive(
                it / 100,
            ); else -> value
            }
        } ?: value

        fun token(value: String) = Regex("[^A-Z0-9]+").replace(value.trim().uppercase(Locale.ROOT), "_").trim('_')
        fun texts(value: JsonElement?): JsonElement? = when (value) {
            is JsonPrimitive -> if (value.isString) JsonArray(listOf(value)) else value
            is JsonArray -> JsonArray(
                value.map { entry ->
                    (entry as? JsonObject)?.get("message")?.takeIf { it is JsonPrimitive && it.isString } ?: entry
                },
            )

            else -> value
        }

        val result = raw.toMutableMap()
        confidence(result["evaluationConfidence"])?.let { result["evaluationConfidence"] = it }
        val statusKey = if ("taskResponseStatus" in result) "taskResponseStatus" else "task_response_status"
        (result[statusKey] as? JsonPrimitive)?.takeIf { it.isString }?.let {
            val value = token(it.content)
            result[statusKey] = JsonPrimitive(
                mapOf(
                    "COMPLETE" to "FULFILLED", "COMPLETED" to "FULFILLED",
                    "PARTIALLY_FULFILLED" to "PARTIAL", "REFUSAL" to "META_REFUSAL", "OFFTOPIC" to "OFF_TOPIC",
                    "EMPTY" to "EMPTY_CONTENT", "EMPTY_RESPONSE" to "EMPTY_CONTENT", "NON_RESPONSE" to "EMPTY_CONTENT",
                )[value] ?: value,
            )
        }
        if (itemType == LevelTestItemType.SPEAKING_REPEAT && "recommendedAnswers" !in result)
            result["recommendedAnswers"] = JsonArray(emptyList())
        (result["recommendedAnswers"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
            result["recommendedAnswers"] = JsonArray(listOf(it))
        }

        // 0~1 점수 형식은 기존 Python이 전체 수치 집합을 확인한 경우에만 100점 척도로 바꾼다.
        (result["metrics"] as? JsonArray)?.let { values ->
            val objects = values.filterIsInstance<JsonObject>()
            val numbers = objects.mapNotNull { numeric(it["score"]) }
            val scale =
                numbers.isNotEmpty() && numbers.size == objects.count { it["score"] != null && it["score"] != JsonNull } &&
                    numbers.all { it in 0.0..1.0 } && numbers.any { it > 0 && it < 1 }
            result["metrics"] = JsonArray(
                values.map { element ->
                    val metric = (element as? JsonObject)?.toMutableMap() ?: return@map element
                    confidence(metric["confidence"])?.let { metric["confidence"] = it }
                    (metric["type"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
                        val value = token(it.content)
                        metric["type"] = JsonPrimitive(
                            if (value in setOf("TASKFULFILLMENT", "TASK_FULFILMENT")) "TASK_FULFILLMENT" else value,
                        )
                    }
                    (metric["state"] as? JsonPrimitive)?.takeIf { it.isString }?.let {
                        val value = token(it.content)
                        metric["state"] = JsonPrimitive(
                            when (value) {
                                "UNEVALUABLE" -> "NOT_EVALUABLE"
                                "EVALUABLE" -> "EVALUATED"
                                else -> value
                            },
                        )
                    }
                    if (scale) numeric(metric["score"])?.let { metric["score"] = JsonPrimitive(it * 100) }
                    texts(metric["evidence"])?.let { metric["evidence"] = it }
                    if (metric["state"] == JsonPrimitive("NOT_EVALUABLE")) {
                        metric["score"] = JsonNull
                        if ((metric["notEvaluableReason"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.isNullOrBlank()) {
                            val reason = when (originLanguage.trim()
                                .lowercase(Locale.ROOT)
                                .substringBefore('-')
                                .substringBefore('_')) {
                                "ko" -> "이 항목을 평가할 근거가 충분하지 않습니다."
                                "ja" -> "この項目を評価するための根拠が十分ではありません。"
                                "en" -> "There is not enough evidence to evaluate this metric."
                                else -> "Insufficient evidence for this metric."
                            }
                            metric["notEvaluableReason"] = JsonPrimitive(reason)
                        }
                        if ((metric["summary"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.isNullOrBlank())
                            metric["summary"] = metric.getValue("notEvaluableReason")
                    }
                    JsonObject(metric)
                },
            )
        }
        listOf("strengths", "improvements").forEach { key -> texts(result[key])?.let { result[key] = it } }
        return JsonObject(result)
    }

    private fun aliases(value: JsonObject): JsonObject {
        val result = value.toMutableMap()
        mapOf(
            "evaluation_confidence" to "evaluationConfidence", "task_response_status" to "taskResponseStatus",
            "recommended_answers" to "recommendedAnswers",
        ).forEach { (old, target) ->
            if (old in result && target !in result) result[target] = result.remove(old)!!
        }
        (result["metrics"] as? JsonArray)?.let { metrics ->
            result["metrics"] = JsonArray(
                metrics.map { element ->
                    val metric = (element as? JsonObject)?.toMutableMap() ?: return@map element
                    if ("not_evaluable_reason" in metric && "notEvaluableReason" !in metric)
                        metric["notEvaluableReason"] = metric.remove("not_evaluable_reason")!!
                    JsonObject(metric)
                },
            )
        }
        return JsonObject(result)
    }

    private fun invalid(): Nothing = throw LevelSpeakingProtocolFailure()
}
