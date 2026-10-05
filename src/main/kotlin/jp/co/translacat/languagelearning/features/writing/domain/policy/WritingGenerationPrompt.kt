package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import java.util.*

/** Current Python generation prompt payload, assembled with LL-owned policy values. */
internal object WritingGenerationPrompt {
    private val blueprints by lazy { resource("production-blueprints.json").jsonObject }
    private val rubric by lazy { resource("band-rubric.json").jsonObject }
    private val scenarioRule by lazy {
        requireNotNull(javaClass.getResource("/writing/scenario-classification-rule.txt"))
            .readText().trim()
    }

    fun build(
        request: JsonObject,
        writingType: WritingType,
        targetBand: Int,
        generationAttempt: Int = 1,
        feedback: Map<String, Int> = emptyMap(),
        sourceRecoveryMode: Boolean = false,
        difficultyRecovery: JsonObject? = null,
    ): String {
        require(generationAttempt in 1..4 && targetBand in 1..5)
        require(request.getValue("writingType").jsonPrimitive.content == writingType.name)
        val originLanguage = request.getValue("originLanguage").jsonPrimitive.content
        val payload = request.filterKeys {
            it !in setOf("sentenceCount", "difficultyDistribution", "languageComplexity") &&
                (difficultyRecovery == null || it !in setOf(
                    "learningProfile", "recentMistakes",
                    "recentlyLearnedExpressions", "recentEvaluationSummary",
                ))
        }.toMutableMap()
        // 언어가 확인되지 않는 누적 profile은 현재 언어의 성취·약점 근거로 모델에 전달하지 않는다.
        // 기존 기준 점수와 서버의 목표 band 정책은 그대로 두고 실제 언어별 최근 평가 문맥을 보존한다.
        val profile = payload["learningProfile"] as? JsonObject
        if (profile != null) {
            payload["learningProfile"] = JsonObject(profile.filterKeys {
                it in setOf("profileVersion", "baseLevelScore")
            } + mapOf("languageScope" to JsonPrimitive("UNKNOWN"), "learningLanguage" to JsonNull))
        }
        for (field in listOf("recentMistakes", "recentlyLearnedExpressions")) {
            if (field in payload) payload[field] = JsonArray(emptyList())
        }
        val recent = payload["recentEvaluationSummary"] as? JsonObject
        if (recent != null && (recent["learningLanguage"] != request["learningLanguage"] ||
                recent["writingType"] != JsonPrimitive(writingType.name) ||
                recent["scoringPolicyVersion"] != JsonPrimitive(WritingScoring.policyVersion) ||
                recent["evaluationRubricVersion"] != JsonPrimitive(WritingScoring.rubricVersion))
        ) {
            // 옛 snapshot의 언어·정책이 불명확한 평균은 없는 점수나 현재 언어 점수로 바꾸지 않는다.
            payload.remove("recentEvaluationSummary")
        }
        if (difficultyRecovery != null) payload["difficultyRecovery"] = difficultyRecovery
        payload["writingDiversityPlan"] = WritingDiversityPolicy.plan(request).payload()
        payload["scenarioClassificationRule"] = JsonPrimitive(scenarioRule)
        payload["productionBlueprint"] = blueprints.getValue("${writingType.name}.B$targetBand.A$generationAttempt")
        payload["generationPlan"] = buildJsonObject {
            put("candidateCount", 2)
            put("targetBand", targetBand)
            put("writingType", writingType.name)
        }
        if (writingType == WritingType.TRANSLATION) {
            val learningLanguage = request.getValue("learningLanguage").jsonPrimitive.content
            payload["sourceLanguageContract"] = sourceLanguageContract(originLanguage, learningLanguage)
        }
        if (sourceRecoveryMode) {
            for (key in listOf(
                "learningProfile", "recentMistakes", "recentlyLearnedExpressions", "recentEvaluationSummary",
            ))
                payload.remove(key)
            val context = (payload["diversityContext"] as? JsonObject).orEmpty()
            payload["diversityContext"] = JsonObject(
                context.mapValues { (_, value) ->
                    if (value is JsonArray && value.firstOrNull() is JsonObject)
                        JsonArray(
                            value.map { entry ->
                                JsonObject(
                                    entry.jsonObject.filterKeys {
                                        it in setOf("scenarioCategory", "communicativeIntent", "contentHash", "ageDays")
                                    },
                                )
                            },
                        )
                    else value
                },
            )
            payload["sourceRecoveryMode"] = JsonPrimitive("REDUCED_CONTEXT_REGENERATION_ONCE")
        }
        // 원문 복구의 축소 문맥은 보존한다. 일반 생성은 이미 게시한 과제 전체와 의미 차이를 확인한다.
        val retained = if (sourceRecoveryMode) emptyList() else WritingDiversityPolicy.retainedTasks(request)
        if (retained.isNotEmpty()) payload["retainedCurrentWritingTasks"] = JsonArray(retained)
        payload["languageProductionRubric"] = rubric
        payload["difficultySpec"] = specPayload(originLanguage, writingType, targetBand)
        payload["failedChecks"] = JsonObject(
            feedback.toSortedMap().entries.take(24)
                .associate { it.key to JsonPrimitive(it.value) },
        )
        val payloadJson = JsonObject(payload).toString().replace("<", "\\u003c").replace(">", "\\u003e")
        val frame = """
            # Current task
            Generate up to 2 distinct content candidates for ONE Writing slot.
            Do not fill server-owned order/difficulty/band fields and do not provide an answer.
            Content will undergo independent difficulty and task-quality checks before publication.
            Follow writingDiversityPlan before choosing the setting/intent; do not merely change labels.
            Realize productionBlueprint.semanticRequirements in the visible source/task, not in metadata.
            These are language-production demands, not an invitation to add technical knowledge or padding.

            <learning-data>
            $payloadJson
            </learning-data>
        """.trimIndent()
        if (retained.isEmpty()) return frame
        return frame.replace("<learning-data>", """
            Compare each candidate with EVERY R-numbered retainedCurrentWritingTasks entry before choosing it.
            Use the visible content, providedFacts, requiredIntents and responseConstraints to identify the
            core facts, communicative purpose and minimum production required for a valid answer.
            Change the actual situation/purpose or required meaning; changing metadata labels, objects,
            names, wording, or a minor timing detail alone does not make the same core task distinct.
            A shared selected topic or grammar pattern is allowed when the required message is different.
            Null guidance means unavailable history, not permission to invent facts or learner weaknesses.

            <learning-data>
        """.trimIndent())
    }

    internal fun sourceLanguageContract(originLanguage: String, learningLanguage: String): JsonObject {
        val pattern = when (originLanguage.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)) {
            "ko" -> "[\\uac00-\\ud7a3\\u1100-\\u11ff]"
            "ja" -> "[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]"
            "en" -> "[A-Za-z]"
            else -> null
        }
        return buildJsonObject {
            put("policyVersion", "writing-source-language-v1")
            put("field", "originText")
            put("sourceLanguage", originLanguage)
            put("learnerAnswerLanguage", learningLanguage)
            put("fieldRole", "SOURCE_SENTENCE_NOT_TRANSLATED_ANSWER")
            put("requiredScriptPattern", pattern?.let(::JsonPrimitive) ?: JsonNull)
            put("technicalNamesAllowed", true)
            put("scriptPresenceIsNotLanguageProof", true)
        }
    }

    internal fun specPayload(originLanguage: String, writingType: WritingType, targetBand: Int): JsonObject {
        val spec = WritingDifficultyPolicy.spec(originLanguage, writingType, targetBand)
        return buildJsonObject {
            put("policyVersion", WritingDifficultyPolicy.version)
            put("targetBand", targetBand)
            put("writingType", writingType.name)
            put(
                "hardConstraints",
                buildJsonObject {
                    put("originMaxCharacters", spec.originMaxCharacters)
                    put("originMaxSurfaceUnits", spec.originMaxSurfaceUnits)
                    put("guidanceMinEntriesPerGroup", spec.guidanceMinEntries)
                    put("guidanceMaxEntriesPerGroup", spec.guidanceMaxEntries)
                    put("guidanceMaxCharactersPerEntry", spec.guidanceMaxCharactersPerEntry)
                    put("guidanceMaxTotalCharacters", spec.guidanceMaxTotalCharacters)
                    put("noteMaxCharacters", spec.noteMaxCharacters)
                    put("characterMeasure", "NFC Unicode code points including internal spaces")
                    put("surfaceUnitMeasure", "nonempty punctuation/newline-delimited units; NOT clauses")
                },
            )
            put("semanticRecipe", spec.semanticRecipe)
            put("semanticRecipeEnforcement", "independent reviewer, not tag counting or regex")
        }
    }

    private fun resource(name: String): JsonElement = Json.parseToJsonElement(
        requireNotNull(javaClass.getResource("/writing/$name")).readText(),
    )
}
