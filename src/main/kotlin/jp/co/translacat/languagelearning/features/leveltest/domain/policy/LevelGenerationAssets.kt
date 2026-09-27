package jp.co.translacat.languagelearning.features.leveltest.domain.policy

import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestItemType
import kotlinx.serialization.json.*

/** 현재 Python 생성 프롬프트 및 문항별 provider Schema 특화를 그대로 구성한다. */
internal object LevelGenerationAssets {
    private val choices = setOf(
        "VOCAB_CONTEXT_CHOICE", "VOCAB_PARAPHRASE_CHOICE", "GRAMMAR_FORM_CHOICE", "GRAMMAR_SENTENCE_ORDER",
        "READING_GIST", "READING_DETAIL", "READING_DISCOURSE_FUNCTION", "READING_TEXT_INFERENCE",
        "LISTENING_GIST_CHOICE", "LISTENING_DETAIL_CHOICE",
    )
    val guided = setOf(
        "WRITING_GUIDED_SENTENCE", "WRITING_SCENARIO_RESPONSE", "WRITING_SHORT_PARAGRAPH", "SPEAKING_GUIDED_RESPONSE",
        "SPEAKING_SHORT_RESPONSE",
    )

    fun instructions(name: String): String = resource("$name-system-prompt.txt")
    fun baseSchema(name: String): JsonObject = Json.parseToJsonElement(resource("$name-schema.json")).jsonObject

    fun schema(request: JsonObject, requirePlan: Boolean): JsonObject {
        val type = request.getValue("itemType").jsonPrimitive.content
        val learning = request.getValue("learningLanguage").jsonPrimitive.content
        val root = baseSchema("generation").toMutableMap()
        val defs = root.getValue("\$defs").jsonObject.toMutableMap()
        val rootProperties = root.getValue("properties").jsonObject.toMutableMap()
        rootProperties["candidates"] = JsonObject(
            rootProperties.getValue("candidates").jsonObject + mapOf(
                "minItems" to JsonPrimitive(2), "maxItems" to JsonPrimitive(2),
            ),
        )
        root["properties"] = JsonObject(rootProperties)

        // 요청한 문항·band·언어를 provider Schema에 고정하고 원본의 설계 ID 계약을 유지한다.
        val candidate = defs.getValue("LevelTestQuestionCandidate").jsonObject.toMutableMap()
        val properties = candidate.getValue("properties").jsonObject.toMutableMap()
        val required = candidate.getValue("required").jsonArray.toMutableList()
        properties["domain"] = enum(request.getValue("domain"))
        properties["itemType"] = enum(request.getValue("itemType"))
        properties["complexityBand"] = buildJsonObject {
            put("type", "integer"); put(
            "enum", JsonArray(listOf(request.getValue("targetComplexityBand"))),
        )
        }
        properties["instructionLanguage"] = enum(JsonPrimitive(learning))
        properties["generationPlanId"] = if (type == "VOCAB_CONTEXT_CHOICE") {
            if (requirePlan) {
                if (JsonPrimitive("generationPlanId") !in required) required += JsonPrimitive("generationPlanId")
                enum(JsonPrimitive("A"), JsonPrimitive("B"))
            } else buildJsonObject {
                put(
                    "anyOf", JsonArray(listOf(enum(JsonPrimitive("A"), JsonPrimitive("B")), nullSchema())),
                )
            }
        } else nullSchema()
        when {
            type in choices -> {
                properties["answerMode"] = enum(JsonPrimitive("CHOICE"))
                properties["answerLanguage"] = nullSchema()
                val options = properties.getValue("options").jsonObject.toMutableMap()
                options["minItems"] = JsonPrimitive(if (type == "GRAMMAR_SENTENCE_ORDER") 2 else 4)
                if (type != "GRAMMAR_SENTENCE_ORDER") options["maxItems"] = JsonPrimitive(4)
                properties["options"] = JsonObject(options)
                if (JsonPrimitive("internalAnswerKey") !in required) required += JsonPrimitive("internalAnswerKey")
            }

            type.startsWith("WRITING_") || type in setOf("LISTENING_DICTATION", "LISTENING_INTERPRETATION") -> {
                properties["answerMode"] = enum(JsonPrimitive("TEXT"))
                properties["answerLanguage"] = enum(
                    if (type == "LISTENING_INTERPRETATION") request.getValue("originLanguage") else JsonPrimitive(
                        learning,
                    ),
                )
            }

            type.startsWith("SPEAKING_") -> {
                properties["answerMode"] = enum(JsonPrimitive("AUDIO"))
                properties["answerLanguage"] = enum(JsonPrimitive(learning))
            }
        }
        candidate["properties"] = JsonObject(properties)
        candidate["required"] = JsonArray(required)
        defs["LevelTestQuestionCandidate"] = JsonObject(candidate)

        // 내부 답 키와 참조 원문의 필수 구조는 해당 문항에만 좁혀 적용한다.
        val answer = defs.getValue("LevelTestInternalAnswerKey").jsonObject.toMutableMap()
        val answerProperties = answer.getValue("properties").jsonObject.toMutableMap()
        if (type in choices) {
            if (type == "GRAMMAR_SENTENCE_ORDER") {
                answerProperties["correctOptionKey"] = nullSchema()
                answerProperties["correctOrder"] =
                    JsonObject(answerProperties.getValue("correctOrder").jsonObject + ("minItems" to JsonPrimitive(2)))
            } else {
                answerProperties["correctOptionKey"] =
                    enum(*listOf("A", "B", "C", "D").map(::JsonPrimitive).toTypedArray())
                (answer["required"] as? JsonArray)?.let { names ->
                    if (JsonPrimitive("correctOptionKey") !in names) answer["required"] =
                        JsonArray(names + JsonPrimitive("correctOptionKey"))
                }
            }
        }
        answer["properties"] = JsonObject(answerProperties)
        defs["LevelTestInternalAnswerKey"] = JsonObject(answer)
        val reference = defs.getValue("LevelTestReferencePayload").jsonObject.toMutableMap()
        val references = reference.getValue("properties").jsonObject.toMutableMap()
        if (type.startsWith("READING_")) listOf("readingPassage", "readingQuestion").forEach {
            references[it] = stringSchema()
        }
        if (type.startsWith("LISTENING_")) listOf("sourceText", "listeningQuestion").forEach {
            references[it] = stringSchema()
        }
        if (type == "LISTENING_INTERPRETATION") {
            references["referenceMeanings"] = strings(2, 3)
            references["keyMeaningUnits"] = strings(2, 5)
        }
        if (type == "SPEAKING_REPEAT") references["referenceText"] = stringSchema()
        if (type == "WRITING_TRANSLATION") references["translationSourceText"] = stringSchema()
        if (type == "READING_DISCOURSE_FUNCTION") references["emphasisText"] = stringSchema()
        if (type in guided) {
            val minimum = if (type in setOf(
                    "WRITING_SCENARIO_RESPONSE", "WRITING_SHORT_PARAGRAPH", "SPEAKING_SHORT_RESPONSE",
                )
            ) 2 else 1
            references["providedFacts"] = strings(minimum)
            references["requiredIntents"] = strings(minimum)
            references["responseConstraints"] = strings(1)
        }
        reference["properties"] = JsonObject(references)
        defs["LevelTestReferencePayload"] = JsonObject(reference)

        val diversity = defs.getValue("DiversityMetadata").jsonObject.toMutableMap()
        val diversityProperties = diversity.getValue("properties").jsonObject.toMutableMap()
        val preferred = request.getValue("preferredScenarioCategories").jsonArray
        if (preferred.isNotEmpty()) diversityProperties["scenarioCategory"] = enum(*preferred.toTypedArray())
        val intents = defs.getValue("CommunicativeIntent").jsonObject.getValue("enum").jsonArray
        if (intents.isNotEmpty()) diversityProperties["communicativeIntent"] = enum(*intents.toTypedArray())
        diversity["properties"] = JsonObject(diversityProperties)
        defs["DiversityMetadata"] = JsonObject(diversity)
        root["\$defs"] = JsonObject(defs)
        return JsonObject(root)
    }

    fun prompt(request: JsonObject, refill: Int, rejected: List<String>, designs: JsonArray?): String {
        val type = request.getValue("itemType").jsonPrimitive.content
        val domain = request.getValue("domain").jsonPrimitive.content
        val number = request.getValue("questionNumber").jsonPrimitive.int
        val band = request.getValue("targetComplexityBand").jsonPrimitive.int
        val selection = LevelGenerationVerificationPolicy.selection(LevelTestItemType.valueOf(type), band)

        // 서명된 업로드 URL은 전달 수단이므로 원본과 동일하게 모델 입력에서 제외한다.
        val payload = JsonObject(request.filterKeys { it != "referenceAudioUpload" })
        return "Generate two candidate questions for question $number/20. The requested domain is $domain, itemType is $type, " +
            "and complexityBand is $band. refillAttempt=$refill. Rejected semantic summaries from earlier attempts: " +
            rejected.joinToString(", ", "[", "]") { JsonPrimitive(it).toString() } + ".\n" +
            (selection?.let { "Server selectionPolicy for this Choice item: $it.\n" } ?: "") +
            (if (!designs.isNullOrEmpty()) "For VOCAB_CONTEXT_CHOICE, implement the two server-approved designs exactly: " +
                "return one candidate per design, set generationPlanId to its designId, and make the correct option text exactly equal to targetExpression. " +
                "The semanticConstraint must be visible in the learner-facing context.\n" +
                buildJsonObject { put("vocabContextDesigns", designs) } + "\n" else
                "No server-approved vocabContextDesigns are supplied for this generation call. Set generationPlanId to null for every candidate; never invent a plan identifier.\n") +
            speakingConstraint(domain, type, number) + "\n" + payload
    }

    private fun speakingConstraint(domain: String, type: String, number: Int): String {
        if (domain != "SPEAKING") return ""
        if (number == 18 && type == "SPEAKING_REPEAT") return "Speaking mode=TEXT_ASSISTED_REPEAT. referenceText will be visible during the test. " +
            "Generate one short natural pronunciation-focused sentence (one sentence, <=90 characters where practical); avoid nested clauses/lists and set maxAudioSeconds<=20.\n"
        if (number == 19 && type == "SPEAKING_REPEAT") return "Speaking mode=AUDIO_ONLY_REPEAT with maxPlaybackCount=3. referenceText will be hidden during the active test. " +
            "Generate one short natural pronunciation-focused sentence (one sentence, <=90 characters where practical); avoid nested clauses/lists and set maxAudioSeconds<=20.\n"
        if (number == 20 && type == "SPEAKING_GUIDED_RESPONSE") return "Speaking mode=GUIDED_OPEN_RESPONSE. Supply concrete facts/intents/constraints so a 2-4 sentence response can satisfy the task.\n"
        return ""
    }

    private fun resource(name: String) =
        checkNotNull(javaClass.getResource("/leveltest/$name")).readText().replace("\r\n", "\n")

    private fun enum(vararg values: JsonElement) =
        buildJsonObject { put("type", "string"); put("enum", JsonArray(values.toList())) }

    private fun nullSchema() = buildJsonObject { put("type", "null") }
    private fun stringSchema() = buildJsonObject { put("type", "string") }
    private fun strings(min: Int, max: Int? = null) = buildJsonObject {
        put("type", "array"); put("items", stringSchema()); put("minItems", min)
        if (max != null) put("maxItems", max)
    }
}
