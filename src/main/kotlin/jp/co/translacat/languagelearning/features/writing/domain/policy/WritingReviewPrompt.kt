package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** 일반 검증에는 목표 band, 학습 프로필, 이전 판정을 제공하지 않는다. */
internal object WritingReviewPrompt {
    val instructions: String by lazy { resourceText("task-review-system-prompt.txt").trimEnd() }
    val adjudicatorInstructions: String by lazy { resourceText("difficulty-review-system-prompt.txt").trimEnd() }
    private val prefix: String by lazy { resourceText("task-review-prefix.txt") }
    private val repairedPrefix: String by lazy { resourceText("repaired-review-prefix.txt") }
    private val recoveredPrefix: String by lazy { resourceText("recovered-review-prefix.txt") }
    private val definitions: JsonObject by lazy {
        Json.parseToJsonElement(resourceText("task-review-definitions.json")).jsonObject
    }

    fun build(
        request: JsonObject, draft: WritingCandidateDraft, candidateId: String, contentHash: String,
        repairPlan: WritingDifficultyRepairPlan? = null,
        sourceRecovery: WritingSourceRecovery.Evidence? = null,
    ): String {
        require(candidateId.isNotBlank() && Regex("[a-f0-9]{64}").matches(contentHash))
        require(repairPlan == null || sourceRecovery == null)

        // 학습자에게 보일 최종 문항만 일반 판정 근거로 나누어 전달한다.
        val segments = buildList {
            add(segment("O1", "originText", draft.originText))
            for ((prefix, name, values) in listOf(
                Triple("F", "providedFacts", draft.providedFacts),
                Triple("I", "requiredIntents", draft.requiredIntents),
                Triple("C", "responseConstraints", draft.responseConstraints),
            )) values.forEachIndexed { index, value -> add(segment("$prefix${index + 1}", name, value)) }
            add(segment("N1", "focusReason", draft.focusReason))
        }
        val selected = (request["selectedKeywords"] as? JsonArray ?: JsonArray(emptyList())).map { keyword ->
            val value = keyword.jsonObject
            buildJsonObject {
                put("key", value.getValue("key"))
                put("text", value.getValue("text"))
                put("type", value.getValue("type"))
            }
        }
        val context = (request["diversityContext"] as? JsonObject ?: JsonObject(emptyMap()))
        val retained = (context["currentSession"] as? JsonArray ?: JsonArray(emptyList()))
            .filter { it.jsonObject["sourceType"]?.jsonPrimitive?.content == "WRITING" }.takeLast(4)
            .map { item ->
                buildJsonObject {
                    put("content", item.jsonObject.getValue("content"))
                    put("semanticSummary", item.jsonObject["semanticSummary"] ?: JsonNull)
                }
            }

        // 보정·원문 복구 증거는 별도 감사 필드에 넣어 일반 난이도 근거와 분리한다.
        val data = buildJsonObject {
            put("candidateId", candidateId)
            put("contentHash", contentHash)
            put("originLanguage", request.getValue("originLanguage"))
            put("learningLanguage", request.getValue("learningLanguage"))
            put("writingType", request.getValue("writingType"))
            put("segments", JsonArray(segments))
            put(
                "diversityAudit",
                buildJsonObject {
                    put("policyVersion", "writing-set-diversity-v1")
                    put(
                        "proposedClassification",
                        buildJsonObject {
                            put("scenarioCategory", draft.metadata.scenarioCategory)
                            put("communicativeIntent", draft.metadata.communicativeIntent)
                            put("taskArchetype", draft.metadata.taskArchetype)
                            put("semanticSummary", draft.metadata.semanticSummary)
                        },
                    )
                    put("claimedKeywordKeys", strings(draft.keywords))
                    put("selectedKeywords", JsonArray(selected))
                    put("retainedCurrentItems", JsonArray(retained))
                },
            )
            put("productionDemandDefinitions", definitions.getValue("productionDemandDefinitions"))
            put("productionGapDefinitions", definitions.getValue("productionGapDefinitions"))
            if (repairPlan != null) put("revisionAudit", repairPlan.reviewPayload(request, draft))
            if (sourceRecovery != null) put("sourceRecovery", sourceRecovery.payload(request, draft))
        }
        val selectedPrefix = when {
            repairPlan != null -> repairedPrefix
            sourceRecovery != null -> recoveredPrefix
            else -> prefix
        }
        return selectedPrefix + "<writing-review-data>\n" + data.toString()
            .replace("<", "\\u003c").replace(">", "\\u003e") + "\n</writing-review-data>"
    }

    private fun segment(id: String, field: String, text: String) = buildJsonObject {
        put("id", id)
        put("field", field)
        put("text", text)
    }

    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun resourceText(name: String) = requireNotNull(javaClass.getResource("/writing/$name"))
        .readText().replace("\r\n", "\n")
}
