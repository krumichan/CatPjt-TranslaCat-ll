package jp.co.translacat.languagelearning.features.listening.domain.policy

import jp.co.translacat.languagelearning.features.listening.domain.model.*
import kotlinx.serialization.json.*
import java.util.*

internal object ListeningSemanticEvaluation {
    private val json = Json { ignoreUnknownKeys = true }
    private val aliases =
        mapOf("NONE" to "INFO", "MINOR" to "LOW", "MODERATE" to "MEDIUM", "MAJOR" to "HIGH", "CRITICAL" to "HIGH")

    fun parse(
        task: ListeningTaskType, raw: JsonElement, meaningUnits: List<String>, context: ListeningEvaluationContext,
    ): ListeningTaskResult {
        require(task in setOf(ListeningTaskType.INTERPRETATION, ListeningTaskType.SUMMARY))
        if (context.revealed) return ListeningScoring.revealed(task, context)

        // Interpretation에 이미 존재하던 값 표준화만 보존한다. Summary에는 적용하지 않는다.
        val objectValue = raw as? JsonObject ?: ListeningSchema.invalid()
        val canonical = if (task == ListeningTaskType.INTERPRETATION) canonicalize(objectValue) else objectValue
        val value = ListeningSchema.decode(
            canonical,
            ListeningAssets.schema(if (task == ListeningTaskType.INTERPRETATION) "interpretation" else "summary"),
        ).jsonObject
        val weights = ListeningScoring.weights.getValue(task)
        val rawMetrics = value.getValue("metrics").jsonArray.map { it.jsonObject }
        val types = rawMetrics.map { it.getValue("type").jsonPrimitive.content }
        if (types.size != weights.size || types.toSet() != weights.keys) ListeningSchema.invalid()
        fun strings(key: String) = (value[key] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        if (task == ListeningTaskType.INTERPRETATION) {
            val returned =
                strings("deliveredMeaningUnits") + strings("omittedMeaningUnits") + strings("misunderstoodMeaningUnits")
            if (returned.any { it !in meaningUnits }) ListeningSchema.invalid()
        }

        // 원본 metric 근거를 보존하며 서버 가중치로만 최종 점수를 계산한다.
        val ordered = if (task == ListeningTaskType.SUMMARY) weights.keys.map { type ->
            rawMetrics.single {
                it.getValue(
                    "type",
                ).jsonPrimitive.content == type
            }
        } else rawMetrics
        val metrics = ordered.map { metric ->
            val type = metric.getValue("type").jsonPrimitive.content
            val evidence = metric.getValue("evidence").jsonArray.map { item ->
                val result = json.decodeFromJsonElement<ListeningEvidence>(item)
                if (result.startMs != null && result.endMs != null && result.endMs < result.startMs) ListeningSchema.invalid()
                if (task == ListeningTaskType.INTERPRETATION) result.copy(metric = type) else result
            }
            ListeningMetric(
                type, metric.getValue("score").jsonPrimitive.double, weights.getValue(type),
                metric.getValue("confidence").jsonPrimitive.double, evidence,
            )
        }
        val confidence = value.getValue("evaluationConfidence").jsonPrimitive.double.let {
            if (task == ListeningTaskType.SUMMARY) minOf(it, metrics.minOf { metric -> metric.confidence }) else it
        }
        return ListeningScoring.finalize(
            ListeningTaskResult(
                task, "EVALUATED", true,
                ListeningScoring.weighted(task, metrics), confidence, metrics = metrics,
                evidence = metrics.flatMap { it.evidence },
                strengths = strings("strengths"), improvements = strings("improvements"),
                recommendedInterpretations = strings(
                    if (task == ListeningTaskType.SUMMARY) "recommendedSummaries" else "recommendedInterpretations",
                ),
                deliveredMeaningUnits = strings(
                    if (task == ListeningTaskType.SUMMARY) "deliveredKeyPoints" else "deliveredMeaningUnits",
                ),
                omittedMeaningUnits = strings(
                    if (task == ListeningTaskType.SUMMARY) "omittedKeyPoints" else "omittedMeaningUnits",
                ),
                misunderstoodMeaningUnits = strings("misunderstoodMeaningUnits"),
                addedInformation = strings("addedInformation"),
            ),
            context,
        )
    }

    private fun canonicalize(raw: JsonObject): JsonObject {
        fun confidence(value: JsonElement?): JsonElement? {
            val numeric = (value as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull ?: return value
            return if (numeric > 1.0 && numeric <= 5.0) JsonPrimitive(numeric / 5.0) else value
        }

        val result = raw.toMutableMap()
        confidence(result["evaluationConfidence"])?.let { result["evaluationConfidence"] = it }
        val metrics = raw["metrics"] as? JsonArray ?: return JsonObject(result)
        val normalizedScale = metrics.isNotEmpty() && metrics.all { element ->
            val score =
                ((element as? JsonObject)?.get("score") as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            score != null && score in 0.0..1.0
        }
        result["metrics"] = JsonArray(
            metrics.map { element ->
                val metric = (element as? JsonObject)?.toMutableMap() ?: return@map element
                if (normalizedScale) metric["score"] =
                    JsonPrimitive(metric.getValue("score").jsonPrimitive.double * 100)
                confidence(metric["confidence"])?.let { metric["confidence"] = it }
                (metric["evidence"] as? JsonArray)?.let { evidence ->
                    metric["evidence"] = JsonArray(
                        evidence.map { entry ->
                            val item = entry as? JsonObject ?: return@map entry
                            val severity =
                                (item["severity"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@map item
                            val normalized = severity.trim().uppercase(Locale.ROOT)
                            JsonObject(item + ("severity" to JsonPrimitive(aliases[normalized] ?: normalized)))
                        },
                    )
                }
                JsonObject(metric)
            },
        )
        return JsonObject(result)
    }

    fun prompt(task: ListeningTaskType, request: JsonObject): String {
        val origin = request.getValue("originLanguage").jsonPrimitive.content
        if (task == ListeningTaskType.SUMMARY) {
            val learning = request.getValue("learningLanguage").jsonPrimitive.content
            return ListeningAssets.instructions(
                "summary",
            ) + "\n\nEvaluate the learner summary. Do not calculate profile signals or the final weighted score. " +
                "Write recommendedSummaries in learningLanguage ($learning) and feedback in originLanguage ($origin).\n\n" + request
        }
        return "Evaluate the learner answer against sourceText and keyMeaningUnits. " +
            "Do not calculate the final weighted score or profile signals. Every evidence.severity MUST be exactly INFO, LOW, MEDIUM, or HIGH. " +
            "Every metrics[].score MUST use a 0 to 100 point scale; return 92, never 0.92. " +
            "evaluationConfidence and every metrics[].confidence MUST be numeric values from 0.0 to 1.0; never use a 1-5 scale or percentage. " +
            "Use only exact request.keyMeaningUnits strings in deliveredMeaningUnits, omittedMeaningUnits, and misunderstoodMeaningUnits. " +
            "Write recommendedInterpretations and all learner-facing feedback in originLanguage ($origin).\n\n" + request
    }
}
