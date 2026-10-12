package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import kotlinx.serialization.json.*

/** 전달·저장 회귀용 합성 근거다. 실제 문장 의미를 평가하는 Provider로 보고하지 않는다. */
internal object CuratedWritingFeedbackTestPayload {
    fun v8Response(input: JsonObject, invalid: Boolean = false): JsonObject {
        // 활성 v8 계약을 합성한다. v9는 실제 출력 별도 재생 테스트에서만 검증한다.
        return buildJsonObject {
            put("observations", buildJsonArray {
                if (!invalid) input.getValue("task").jsonObject.getValue("requirements").jsonArray
                    .forEach { add(observation(input, it.jsonObject)) }
            })
            put("necessaryCorrections", buildJsonArray {})
            put("optionalAlternatives", buildJsonArray {})
            put("uncertainties", buildJsonArray {})
        }
    }

    fun observation(input: JsonObject, requirement: JsonObject): JsonObject {
        val task = input.getValue("task").jsonObject
        val segment = input.getValue("learnerAnswer").jsonObject.getValue("segments").jsonArray.first().jsonObject
        return buildJsonObject {
            put("requirementId", requirement.getValue("id"))
            put("requirementParts", buildJsonArray { add(buildJsonObject {
                val units = requirement["sourceUnits"]?.jsonArray
                if (units == null) {
                    put("requirementQuote", requirement.getValue("text"))
                } else {
                    put("startUnitId", units.first().jsonObject.getValue("id"))
                    put("endUnitId", units.last().jsonObject.getValue("id"))
                    put("taskConditionIds", JsonArray(task.getValue("prescribedConditions").jsonArray.map { it.jsonObject.getValue("id") }))
                }
                put("status", "PRESENT")
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
