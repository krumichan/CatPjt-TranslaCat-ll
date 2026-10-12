package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import kotlinx.serialization.json.*

/** 전달·저장 회귀용 합성 근거다. 실제 문장 의미를 평가하는 Provider로 보고하지 않는다. */
internal object CuratedWritingFeedbackTestPayload {
    fun observation(input: JsonObject, requirement: JsonObject): JsonObject {
        val task = input.getValue("task").jsonObject
        val segment = input.getValue("learnerAnswer").jsonObject.getValue("segments").jsonArray.first().jsonObject
        return buildJsonObject {
            put("requirementId", requirement.getValue("id"))
            put("requirementParts", buildJsonArray { add(buildJsonObject {
                put("requirementQuote", requirement.getValue("text")); put("status", "PRESENT")
                put("evidence", buildJsonArray { add(buildJsonObject {
                    put("segmentId", segment.getValue("id")); put("quote", segment.getValue("text"))
                }) })
                put("taskFactIds", JsonArray(task.getValue("explicitTaskFacts").jsonArray.map { it.jsonObject.getValue("id") }))
            }) })
            put("relationChecks", buildJsonObject {
                listOf("causality", "chronology", "specificity", "stance").forEach { put(it, "PRESERVED") }
            })
        }
    }
}
