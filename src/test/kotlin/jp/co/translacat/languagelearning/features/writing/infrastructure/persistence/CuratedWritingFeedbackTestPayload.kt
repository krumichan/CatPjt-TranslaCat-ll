package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import kotlinx.serialization.json.*

/** 전달·저장 회귀용 합성 근거다. 실제 문장 의미를 평가하는 Provider로 보고하지 않는다. */
internal object CuratedWritingFeedbackTestPayload {
    fun answer(input: JsonObject): String {
        val value = input.getValue("learnerAnswer")
        return if (value is JsonObject) value.getValue("text").jsonPrimitive.content else value.jsonPrimitive.content
    }

    fun v12Response(command: ModelExecutionCommand, invalid: Boolean = false): JsonObject {
        val input = Json.parseToJsonElement(command.messages.single().content).jsonObject
        if (command.schemaName != "writing_independent_evidence") return v11Response(input, invalid)

        // 실제 모델의 의미 판단을 흉내 내지 않고 테스트 답안의 정확한 원문을 구조 근거로 제공한다.
        val requirements = input.getValue("task").jsonObject.getValue("requirements").jsonObject.keys
        val answer = answer(input)
        return buildJsonObject {
            put("statuses", buildJsonObject { requirements.forEach { put(it, "MET") } })
            put("evidence", buildJsonObject {
                requirements.forEach { put(it, buildJsonArray { add(answer) }) }
            })
        }
    }

    fun v11Response(input: JsonObject, invalid: Boolean = false): JsonObject {
        // 구조/저장 회귀용 합성 답안이다. 실제 의미 충족 여부를 보장하는 근거로 사용하지 않는다.
        val segment = input.getValue("learnerAnswer").jsonObject.getValue("segments").jsonArray.first().jsonObject
        val requirements = input.getValue("task").jsonObject.getValue("requirements").jsonArray
        val facts = input.getValue("task").jsonObject.getValue("explicitTaskFacts").jsonArray
        val conditions = input.getValue("task").jsonObject.getValue("prescribedConditions").jsonArray
        return buildJsonObject {
            put("answerObservations", buildJsonArray { add(buildJsonObject {
                put("id", "O1")
                put("segmentId", segment.getValue("id"))
                put("quote", segment.getValue("text"))
                put("meaning", "学習者が文を述べた")
                put("kind", "STATE")
                put("modality", "AFFIRMED")
            }) })
            put("observations", buildJsonObject {
                if (!invalid) requirements.forEach { raw ->
                    val requirement = raw.jsonObject
                    val id = requirement.getValue("id").jsonPrimitive.content
                    put(id, buildJsonObject {
                        put("unitAssignments", buildJsonObject {
                            requirement.getValue("sourceUnits").jsonArray.forEach { unit ->
                                put(unit.jsonObject.getValue("id").jsonPrimitive.content, "$id.S1")
                            }
                        })
                        put("parts", buildJsonArray { add(buildJsonObject {
                            put("id", "$id.S1")
                            put("status", "PRESENT")
                            put("answerObservationIds", buildJsonArray { add("O1") })
                            put("taskFactIds", JsonArray(facts.map { it.jsonObject.getValue("id") }))
                            put("taskConditionIds", JsonArray(conditions.map { it.jsonObject.getValue("id") }))
                        }) })
                        put("relationChecks", buildJsonObject {
                            listOf("causality", "chronology", "specificity", "stance").forEach {
                                put(it, "PRESERVED")
                            }
                        })
                    })
                }
            })
            put("necessaryCorrections", buildJsonArray {})
            put("optionalAlternatives", buildJsonArray {})
            put("uncertainties", buildJsonArray {})
        }
    }

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
