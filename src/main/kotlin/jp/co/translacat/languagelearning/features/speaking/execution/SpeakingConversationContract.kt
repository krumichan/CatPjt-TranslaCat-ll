package jp.co.translacat.languagelearning.features.speaking.execution

import jp.co.translacat.languagelearning.shared.schema.PydanticSchema
import kotlinx.serialization.json.*

/** 원본 프롬프트와 Pydantic Schema를 사용하며 모드 간 필드 검증은 Kotlin에서 수행한다. */
internal object SpeakingConversationContract {
    private val assets = Json.parseToJsonElement(
        checkNotNull(
            javaClass.getResource(
                "/speaking/conversation-assets.json",
            ),
        ).readText(),
    ).jsonObject
    private val decoder = PydanticSchema(defaultObjectProperties = setOf("sessionPolicySnapshot"))
    fun schema(kind: String, target: String): JsonObject = assets.getValue(kind + target).jsonObject
    fun instructions(kind: String): String = assets.getValue(kind + "Instructions").jsonPrimitive.content

    fun request(kind: String, value: JsonObject): JsonObject {
        val decoded = decoder.decode(value, schema(kind, "Request"))
        if (kind == "conversation") {
            // 원본 요청 validator의 권장 시작 방식과 READ_ALOUD 문제/시도 결합을 보존한다.
            if (decoded.string("conversationStartMode") == "TOPIC_RECOMMENDED")
                require(decoded["topicRecommendedStartMode"] != JsonNull) { "SPEAKING_REQUEST_INVALID" }
            if (decoded.string("practiceMode") == "READ_ALOUD") {
                if (decoded.integer("turnIndex") > 0) require(
                    decoded["problemIndex"] != JsonNull &&
                        decoded["attemptIndex"] != JsonNull,
                ) { "SPEAKING_REQUEST_INVALID" }
            } else require(decoded["problemIndex"] == JsonNull && decoded["attemptIndex"] == JsonNull) {
                "SPEAKING_REQUEST_INVALID"
            }
        }
        return decoded
    }

    fun payload(kind: String, request: JsonObject, output: JsonElement): JsonObject {
        val value = decoder.decode(output, schema(kind, "Payload"))
        if (kind == "assistance") {
            require(value["type"] == request["assistanceType"])
            return value
        }

        // 단순 구조 검증 뒤 초기 키워드 주제와 연습 모드의 실제 업무 조건을 별도로 확인한다.
        if (request.boolean("isInitialTurn") && request["category"] == JsonPrimitive("KEYWORDS"))
            require(!value["resolvedTopic"]?.jsonPrimitive?.contentOrNull.isNullOrBlank())
        when (request.string("practiceMode")) {
            "READ_ALOUD" -> {
                require(
                    !value["scriptText"]?.jsonPrimitive?.contentOrNull.isNullOrBlank() &&
                        value.string("scriptText").trim() == value.string("assistantText").trim(),
                )
                require(
                    listOf("providedFacts", "requiredIntents", "responseConstraints")
                        .all { value.getValue(it).jsonArray.isEmpty() },
                )
            }

            "GUIDED" -> {
                require(value["scriptText"] == JsonNull)
                require(
                    listOf("providedFacts", "requiredIntents", "responseConstraints")
                        .all { value.getValue(it).jsonArray.isNotEmpty() },
                )
            }

            "FREE" -> require(value["scriptText"] == JsonNull)
            else -> error("SPEAKING_REQUEST_INVALID")
        }
        return value
    }

    fun assistanceLevel(request: JsonObject): String {
        val types = (request["assistanceUsage"] as? JsonArray).orEmpty()
            .map { it.jsonObject.string("type") }.toSet()
        return when {
            "SAMPLE_ANSWER" in types -> "GUIDED"
            "HINT" in types || "TRANSLATION" in types -> "ASSISTED"
            else -> "NONE"
        }
    }

    fun prompt(kind: String, request: JsonObject): String {
        val fields = if (kind == "conversation") listOf(
            "requestId", "sessionId", "turnIndex", "problemIndex", "attemptIndex",
            "readAloudGenerateNextProblem", "originLanguage", "learningLanguage", "topic",
            "practiceMode", "category", "goal", "persona", "conversationStartMode",
            "topicRecommendedStartMode", "correctionMode", "targetLevel", "learningProfileSummary",
            "selectedKeywords", "focusSignals", "conversationHistory", "sessionSummary",
            "sessionElapsedSeconds", "transcript", "assistanceUsage", "assistanceLevel",
            "isInitialTurn", "sessionPolicySnapshot",
        ) else listOf(
            "requestId", "sessionId", "turnIndex", "assistanceType", "originLanguage",
            "learningLanguage", "topic", "targetLevel", "assistantText", "conversationHistory",
            "selectedKeywords", "sessionSummary",
        )
        val requestSchema = schema(kind, "Request")
        val payload = buildJsonObject {
            fields.forEach { name ->
                val value = when (name) {
                    "assistanceLevel" -> JsonPrimitive(assistanceLevel(request))
                    "conversationHistory" -> JsonArray(
                        request.getValue(name).jsonArray
                            .takeLast(if (kind == "conversation") 12 else 8),
                    )

                    else -> request.getValue(name)
                }
                // Pydantic model_dump를 사용하던 중첩 모델만 같은 숫자 직렬화 형태로 투영한다.
                put(
                    name,
                    if (value is JsonObject || value is JsonArray) wire(
                        value,
                        requestSchema.getValue("properties").jsonObject.getValue(name).jsonObject, requestSchema,
                    )
                    else value,
                )
            }
        }
        val prefix = if (kind == "conversation")
            "Generate the next assistant turn from this session context.\n" +
                "If isInitialTurn=true, generate the first task/prompt for the selected practiceMode.\n" +
                "If correctionMode=COACHING, return coachingCorrections only when useful.\n\n"
        else instructions(kind) + "\n\nGenerate the requested assistance.\n"
        return prefix + payload.toString()
    }

    private fun wire(value: JsonElement, definition: JsonObject, root: JsonObject): JsonElement {
        if (value == JsonNull) return value
        definition["\$ref"]?.jsonPrimitive?.content?.let {
            return wire(value, root.getValue("\$defs").jsonObject.getValue(it.substringAfterLast('/')).jsonObject, root)
        }
        definition["anyOf"]?.jsonArray?.let { options ->
            return wire(value, options.first { it.jsonObject["type"] != JsonPrimitive("null") }.jsonObject, root)
        }
        return when (definition["type"]?.jsonPrimitive?.content) {
            "number" -> JsonPrimitive(value.jsonPrimitive.double)
            "object" -> JsonObject(
                value.jsonObject.mapValues { (key, entry) ->
                    val child = (definition["properties"] as? JsonObject)?.get(key) as? JsonObject
                    if (child == null) entry else wire(entry, child, root)
                },
            )

            "array" -> JsonArray(value.jsonArray.map { wire(it, definition.getValue("items").jsonObject, root) })
            else -> value
        }
    }
}

internal fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
internal fun JsonObject.integer(key: String): Int = getValue(key).jsonPrimitive.int
internal fun JsonObject.boolean(key: String): Boolean = getValue(key).jsonPrimitive.boolean
