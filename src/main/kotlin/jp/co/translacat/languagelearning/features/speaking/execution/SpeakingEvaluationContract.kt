package jp.co.translacat.languagelearning.features.speaking.execution

import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.shared.schema.PydanticSchema
import kotlinx.serialization.json.*

/** 기존 Python의 요청·출력 Schema와 증거 교차 검증을 보존한다. */
internal object SpeakingEvaluationContract {
    private val assets = Json.parseToJsonElement(
        checkNotNull(
            javaClass.getResource(
                "/speaking/evaluation-assets.json",
            ),
        ).readText(),
    ).jsonObject
    private val decoder = PydanticSchema()
    fun schema(kind: String, target: String): JsonObject = assets.getValue(kind + target).jsonObject
    fun instructions(kind: String): String = assets.getValue(kind + "Instructions").jsonPrimitive.content

    fun request(kind: String, raw: JsonObject): JsonObject {
        val value = decoder.decode(raw, schema(kind, "Request"))
        // 사용자·참고 발화의 식별자와 원본 STT 구간 validator를 먼저 확인한다.
        for (key in listOf("userTurns", "assistantTurns")) {
            val ids = value.objects(key).map { it.string("turnId") }
            require(ids.size == ids.distinct().size)
        }
        value.objects("userTurns").flatMap { it.objects("segments") }.forEach {
            require(it.integer("endMs") >= it.integer("startMs"))
        }
        if (kind == "coaching") {
            require(value.string("resultKind") == "SESSION_COACHING")
            require(value.string("practiceMode") == "FREE" && value.string("evaluationScope") == "SESSION")
        }
        return value
    }

    fun turn(value: JsonObject) = SpeakingEvidenceTurn(
        value.string("turnId"), value.string("transcript"), value.number("sttConfidence"),
        value.number("durationSeconds"), value.boolean("excludedFromEvaluation"),
        value.objects("segments").map { it.number("confidence") },
    )

    fun usable(value: JsonObject): Boolean = SpeakingPolicy.transcriptUsable(turn(value))
    fun mode(request: JsonObject) = SpeakingPracticeMode.valueOf(request.string("practiceMode"))
    fun unavailable(request: JsonObject): Map<String, String> =
        SpeakingPolicy.unsupportedMetrics(mode(request)).mapKeys { it.key.name }

    fun bounds(request: JsonObject): Map<String, Int> = request.objects("userTurns")
        .filterNot { it.boolean("excludedFromEvaluation") }
        .associate { it.string("turnId") to ((it.number("durationSeconds") * 1000).toInt() + 250) }

    fun references(request: JsonObject): Map<String, String?> {
        val users = request.objects("userTurns")
        val last = users.maxOf { it.integer("turnIndex") }
        val assistants = request.objects("assistantTurns").filter { it.integer("turnIndex") < last }
            .associate { it.integer("turnIndex") to it.string("turnId") }
        return users.associate { it.string("turnId") to assistants[it.integer("turnIndex") - 1] }
    }

    fun providerSchema(kind: String, request: JsonObject): JsonObject {
        val original = schema(kind, "Payload")
        val definitions = original.getValue("\$defs").jsonObject.toMutableMap()
        if (kind == "coaching") {
            val item = definitions.getValue("SpeakingCoachingDraftItem").jsonObject
            definitions["SpeakingCoachingDraftItem"] = item.property("turnId") { field ->
                JsonObject(
                    field + ("enum" to strings(
                        request.objects("userTurns").filter(::usable).map { it.string("turnId") },
                    )),
                )
            }
            return JsonObject(original + ("\$defs" to JsonObject(definitions)))
        }

        // 모델 Schema에도 각 발화별 실제 시간 상한과 텍스트 평가 가능 축을 전달한다.
        val bounds = bounds(request)
        require(bounds.isNotEmpty())
        val evidence = definitions.getValue("MetricEvidence").jsonObject
        definitions["MetricEvidence"] = buildJsonObject {
            put(
                "anyOf",
                JsonArray(
                    bounds.map { (id, maximum) ->
                        var branch = evidence.property("turnId") { JsonObject(it + ("enum" to strings(listOf(id)))) }
                        for (key in listOf("startMs", "endMs")) branch = branch.property(key) { field ->
                            JsonObject(
                                field + ("anyOf" to JsonArray(
                                    field.getValue("anyOf").jsonArray.map {
                                        if (it.jsonObject["type"] == JsonPrimitive("integer"))
                                            JsonObject(it.jsonObject + ("maximum" to JsonPrimitive(maximum))) else it
                                    },
                                )),
                            )
                        }
                        branch
                    },
                ),
            )
        }
        for (key in listOf("SpeakingProfileSignal", "RecommendedExpression", "PronunciationPractice")) {
            definitions[key] = definitions.getValue(key).jsonObject.property("evidenceTurnIds") {
                JsonObject(
                    it + ("items" to JsonObject(
                        it.getValue("items").jsonObject +
                            ("enum" to strings(bounds.keys)),
                    )),
                )
            }
        }
        val metric = definitions.getValue("SpeakingMetricPayload").jsonObject
        val supported = SpeakingPolicy.weights.keys.map { it.name }.filter { it !in unavailable(request) }
        val branches = supported.map { axis ->
            metric.property("type") {
                buildJsonObject { put("type", "string"); put("enum", strings(listOf(axis))) }
            }
        }
        definitions["SpeakingMetricPayload"] = if (branches.size == 1) branches.single()
        else buildJsonObject { put("anyOf", JsonArray(branches)) }
        var result = JsonObject(original + ("\$defs" to JsonObject(definitions)))
            .property("metrics") {
                JsonObject(
                    it + mapOf("minItems" to JsonPrimitive(branches.size), "maxItems" to JsonPrimitive(branches.size)),
                )
            }
            .property("pronunciationPractice") { JsonObject(it + ("maxItems" to JsonPrimitive(0))) }
        if (mode(request) == SpeakingPracticeMode.READ_ALOUD)
            result = result.property("profileSignals") { JsonObject(it + ("maxItems" to JsonPrimitive(0))) }
        return result
    }

    fun payload(kind: String, request: JsonObject, raw: JsonElement): JsonObject {
        // 기존 서버 소유 축의 사유 보완만 수행하고 이후 모든 원본 검증을 적용한다.
        val value = decoder.decode(
            if (kind == "evaluation") completeMetrics(request, raw) else raw,
            schema(kind, "Payload"),
        )
        if (kind == "coaching") {
            validateCoaching(request, value)
        } else {
            validateEvaluation(request, value)
        }
        return value
    }

    private fun completeMetrics(request: JsonObject, raw: JsonElement): JsonElement {
        val value = raw as? JsonObject ?: return raw
        val metrics = value["metrics"] as? JsonArray ?: return raw
        val unavailable = unavailable(request)
        val supported = SpeakingPolicy.weights.keys.map { it.name }.filter { it !in unavailable }.toSet()
        val returned = metrics.map { (it as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull }
        val completed = if (returned.size == supported.size && returned.toSet() == supported) {
            val byType = metrics.associateBy { it.jsonObject.string("type") }
            SpeakingPolicy.weights.keys.map { axis ->
                byType[axis.name] ?: buildJsonObject {
                    put("type", axis.name); put("state", "NOT_EVALUABLE"); put("score", JsonNull)
                    put("confidence", 1.0); put("summary", "This evaluator has no evidence for this metric.")
                    put("evidence", JsonArray(emptyList())); put("notEvaluableReason", unavailable.getValue(axis.name))
                }
            }
        } else metrics.map { entry ->
            val metric = entry as? JsonObject ?: return@map entry
            val axis = metric["type"]?.jsonPrimitive?.contentOrNull
            if (axis != null && axis in unavailable && metric["state"] == JsonPrimitive("NOT_EVALUABLE") &&
                (metric["score"] == null || metric["score"] == JsonNull) && metric["evidence"] == JsonArray(
                    emptyList(),
                ) &&
                (metric["notEvaluableReason"] == null || metric["notEvaluableReason"] == JsonNull)
            )
                JsonObject(metric + ("notEvaluableReason" to JsonPrimitive(unavailable.getValue(axis)))) else metric
        }
        return JsonObject(value + ("metrics" to JsonArray(completed)))
    }

    private fun validateEvaluation(request: JsonObject, payload: JsonObject) {
        val metrics = payload.objects("metrics")
        val bounds = bounds(request)
        val usable = request.objects("userTurns").filter(::usable).map { it.string("turnId") }.toSet()
        val unavailable = unavailable(request)
        val evaluated = metrics.filter { it.string("state") == "EVALUATED" }.map { it.string("type") }.toSet()

        // Pydantic의 metric 상태/시간 교차 검증 뒤 전체8축 중복과 발화 소유권을 확인한다.
        for (metric in metrics) {
            if (metric.string("state") == "EVALUATED") {
                require(metric["score"] != JsonNull && metric.objects("evidence").isNotEmpty())
            } else {
                require(
                    metric["score"] == JsonNull && !metric["notEvaluableReason"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty(),
                )
            }
            for (evidence in metric.objects("evidence")) {
                val start = evidence["startMs"]?.jsonPrimitive?.intOrNull
                val end = evidence["endMs"]?.jsonPrimitive?.intOrNull
                require(start == null || end == null || end >= start)
                val maximum = bounds[evidence.string("turnId")]
                require(maximum != null && (start == null || start <= maximum) && (end == null || end <= maximum))
            }
        }
        SpeakingPolicy.requireMetricSet(metricValues(payload))
        for (field in listOf("profileSignals", "recommendedExpressions", "pronunciationPractice"))
            payload.objects(field).forEach { require(it.texts("evidenceTurnIds").all(bounds::containsKey)) }

        // STT confidence는 음향 증거로 승격하지 않으며 불확실 전사는 평가 근거로 쓰지 않는다.
        for (metric in metrics) {
            if (metric.string("type") in unavailable) require(
                metric.string("state") == "NOT_EVALUABLE" &&
                    metric["score"] == JsonNull && metric.objects("evidence").isEmpty(),
            )
            if (metric.string("state") == "EVALUATED") require(
                metric.objects("evidence").all { it.string("turnId") in usable },
            )
        }
        require(payload.objects("pronunciationPractice").isEmpty())
        if (mode(request) == SpeakingPracticeMode.READ_ALOUD) require(payload.objects("profileSignals").isEmpty())
        for (signal in payload.objects("profileSignals")) {
            require(signal.string("metricType") in evaluated && signal.string("metricType") !in unavailable)
            val ids = signal.texts("evidenceTurnIds")
            require(ids.distinct().size >= 2 && ids.all { it in usable })
        }
        for (expression in payload.objects("recommendedExpressions")) {
            val ids = expression.texts("evidenceTurnIds")
            require(ids.isNotEmpty() && ids.all { it in usable })
        }
        val hasReference = mode(request) != SpeakingPracticeMode.READ_ALOUD ||
            request.objects("assistantTurns").any { !it["scriptText"]?.jsonPrimitive?.contentOrNull.isNullOrEmpty() }
        if (usable.isNotEmpty() && hasReference) require(evaluated.isNotEmpty())
    }

    private fun validateCoaching(request: JsonObject, payload: JsonObject) {
        val items = payload.objects("items")
        val ids = items.map { it.string("observationId") }
        require(ids.distinct().size == ids.size)
        if (payload.string("contentStatus") == "GROUNDED") require(items.isNotEmpty())
        if (payload.string("contentStatus") == "NO_USABLE_EVIDENCE") require(items.isEmpty())

        // 인용은 실제 사용 가능한 전사의 연속 부분 문자열이어야 한다. 내부 필드는 학습자 문구에 노출하지 않는다.
        val turns = request.objects("userTurns").filter(::usable).associateBy { it.string("turnId") }
        val forbidden = listOf("turnId", "recordingRevision", "sourceSnapshotHash", "metadata")
        for (item in items) {
            val turn = turns[item.string("turnId")]
            require(turn != null && item.string("sourceExcerpt") in turn.string("transcript"))
            require(forbidden.none { it in item.string("message") })
            val suggestion = item["suggestedExpression"]?.jsonPrimitive?.contentOrNull
            require(suggestion == null || forbidden.none { it in suggestion })
        }
    }

    fun metricValues(payload: JsonObject): List<SpeakingMetricValue> = payload.objects("metrics").map {
        SpeakingMetricValue(
            SpeakingMetricType.valueOf(it.string("type")),
            SpeakingMetricState.valueOf(it.string("state")), it["score"]?.jsonPrimitive?.doubleOrNull,
        )
    }

    fun prefix(kind: String): String = assets.getValue(kind + "Prefix").jsonPrimitive.content
}

internal fun JsonObject.objects(key: String): List<JsonObject> = getValue(key).jsonArray.map { it.jsonObject }
internal fun JsonObject.texts(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }
internal fun JsonObject.number(key: String): Double = getValue(key).jsonPrimitive.double
internal fun strings(values: Iterable<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))
private fun JsonObject.property(key: String, transform: (JsonObject) -> JsonObject): JsonObject {
    val properties = getValue("properties").jsonObject
    return JsonObject(
        this + ("properties" to JsonObject(properties + (key to transform(properties.getValue(key).jsonObject)))),
    )
}
