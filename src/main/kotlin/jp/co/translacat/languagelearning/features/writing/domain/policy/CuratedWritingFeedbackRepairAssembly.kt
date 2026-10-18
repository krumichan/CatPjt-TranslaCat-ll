package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.policy.CuratedWritingFeedbackV3Policy.Segment
import kotlinx.serialization.json.*

/** 복구 모델은 의미와 경계만 결정하고, 연속 구간·식별자·필수 권한 연결은 서버가 조립한다. */
internal object CuratedWritingFeedbackRepairAssembly {
    private const val japanesePattern = "^[^\\u1100-\\u11ff\\u3130-\\u318f\\ua960-\\ua97f\\uac00-\\ud7ff\\uffa0-\\uffdc]*$"

    fun schema(context: CuratedWritingFeedbackPolicy.Context,
               requirements: List<Pair<String, String>>, segments: List<Segment>): JsonObject {
        val prior = CuratedWritingFeedbackV11Policy.schema(context, requirements, segments)
            .getValue("properties").jsonObject
        val observationIds = (1..maxOf(16, segments.size * 4)).map { "O$it" }
        val units = requirements.associate { (id, source) -> id to CuratedWritingRequirementUnits.compile(id, source) }
        val facts = CuratedWritingFeedbackPolicy.taskFacts(context).map { it.first }
        val conditions = CuratedWritingFeedbackPolicy.taskConditions(context).map { it.first }
        val end = obj(linkedMapOf(
            "status" to text(listOf("PRESENT", "ABSENT", "CONFLICT", "UNCERTAIN")),
            "answerObservationIds" to array(text(observationIds)),
        ))
        val endRef = buildJsonObject { put("\$ref", "#/\$defs/partEnd") }
        val nullableEnd = buildJsonObject {
            put("anyOf", buildJsonArray { add(endRef); add(buildJsonObject { put("type", "null") }) })
        }
        val observations = obj(units.mapValues { (id, sourceUnits) ->
            val original = prior.getValue("observations").jsonObject.getValue("properties").jsonObject
                .getValue(id).jsonObject.getValue("properties").jsonObject
            obj(linkedMapOf(
                "unitEnds" to obj(sourceUnits.mapIndexed { index, unit ->
                    unit.id to if (index == sourceUnits.lastIndex) endRef else nullableEnd
                }.toMap()),
                "relationChecks" to original.getValue("relationChecks"),
            ))
        })
        val unitRef = buildJsonObject { put("\$ref", "#/\$defs/unitId") }
        val authorityLinks = obj((facts + conditions).associateWith { array(unitRef, minimum = 1) })

        // 교정 종류마다 허용된 권한 조합만 제시한다. 한국어 설명은 삭제하지 않고 생성 단계에서 금지한다.
        val correction = prior.getValue("necessaryCorrections").jsonObject.getValue("items").jsonObject
            .getValue("properties").jsonObject
        fun family(type: String, factIds: List<String>, conditionIds: List<String>, forms: List<String>) =
            obj(correction + mapOf(
                "issueType" to text(listOf(type)), "sourceFactId" to text(factIds),
                "sourceAuthorityId" to text(conditionIds), "formReason" to text(forms),
            ))
        val families = mutableListOf(family("LANGUAGE_FORM", listOf(""), listOf(""),
            listOf("SPELLING", "INFLECTION", "SYNTAX")))
        if (facts.isNotEmpty()) families += family("TASK_FACT_CONFLICT", facts, listOf(""), listOf("NONE"))
        if (conditions.isNotEmpty()) families += family("TASK_CONDITION_CONFLICT", listOf(""), conditions, listOf("NONE"))
        val root = obj(linkedMapOf(
            "answerObservations" to prior.getValue("answerObservations"),
            "observations" to observations,
            "authorityLinks" to authorityLinks,
            "necessaryCorrections" to array(buildJsonObject { put("anyOf", JsonArray(families)) }),
            "optionalAlternatives" to prior.getValue("optionalAlternatives"),
            "uncertainties" to prior.getValue("uncertainties"),
        ))
        return japaneseOnly(JsonObject(root + ("\$defs" to buildJsonObject {
            put("partEnd", end)
            put("unitId", text(units.values.flatten().map { it.id }))
        }))).jsonObject
    }

    fun assemble(output: JsonElement, context: CuratedWritingFeedbackPolicy.Context,
                 requirements: List<Pair<String, String>>): JsonObject {
        try {
            val root = output.jsonObject
            require(root.keys == setOf("answerObservations", "observations", "authorityLinks",
                "necessaryCorrections", "optionalAlternatives", "uncertainties"))
            val decisions = root.getValue("observations").jsonObject
            require(decisions.keys == requirements.map { it.first }.toSet())
            val units = requirements.associate { (id, source) -> id to CuratedWritingRequirementUnits.compile(id, source) }
            val knownUnits = units.values.flatten().map { it.id }.toSet()
            val factIds = CuratedWritingFeedbackPolicy.taskFacts(context).map { it.first }.toSet()
            val conditionIds = CuratedWritingFeedbackPolicy.taskConditions(context).map { it.first }.toSet()
            val links = root.getValue("authorityLinks").jsonObject
            require(links.keys == factIds + conditionIds)
            val destinations = links.mapValues { (_, value) ->
                value.jsonArray.map { it.jsonPrimitive.content }.also {
                    require(it.isNotEmpty() && it.size == it.distinct().size && it.all(knownUnits::contains))
                }.toSet()
            }

            // 마지막 단위를 필수 끝점으로 고정하므로 각 원문 위치는 순서대로 정확히 한 번 할당된다.
            val normalized = units.mapValues { (id, sourceUnits) ->
                val row = decisions.getValue(id).jsonObject
                require(row.keys == setOf("unitEnds", "relationChecks"))
                val ends = row.getValue("unitEnds").jsonObject
                require(ends.keys == sourceUnits.map { it.id }.toSet())
                require(ends.getValue(sourceUnits.last().id) != JsonNull)
                val assignments = linkedMapOf<String, JsonElement>()
                val parts = mutableListOf<JsonElement>()
                var cursor = 0
                sourceUnits.forEachIndexed { index, unit ->
                    val raw = ends.getValue(unit.id)
                    if (raw != JsonNull) {
                        val part = raw.jsonObject
                        require(part.keys == setOf("status", "answerObservationIds"))
                        val partId = "$id.S${parts.size + 1}"
                        val covered = sourceUnits.subList(cursor, index + 1).map { it.id }.toSet()
                        covered.forEach { assignments[it] = JsonPrimitive(partId) }
                        parts += JsonObject(part + mapOf(
                            "id" to JsonPrimitive(partId),
                            "taskFactIds" to JsonArray(factIds.filter { authority ->
                                destinations.getValue(authority).any(covered::contains)
                            }.map(::JsonPrimitive)),
                            "taskConditionIds" to JsonArray(conditionIds.filter { authority ->
                                destinations.getValue(authority).any(covered::contains)
                            }.map(::JsonPrimitive)),
                        ))
                        cursor = index + 1
                    }
                }
                require(cursor == sourceUnits.size)
                buildJsonObject {
                    put("unitAssignments", JsonObject(assignments))
                    put("parts", JsonArray(parts))
                    put("relationChecks", row.getValue("relationChecks"))
                }
            }
            return JsonObject(root - "authorityLinks" + ("observations" to JsonObject(normalized)))
        } catch (_: Exception) {
            throw IllegalArgumentException("WRITING_FEEDBACK_REPAIR_ASSEMBLY_INVALID")
        }
    }

    private fun text(values: List<String>) = buildJsonObject {
        put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive)))
    }

    private fun obj(fields: Map<String, JsonElement>) = buildJsonObject {
        put("type", "object"); put("properties", JsonObject(fields)); put("additionalProperties", false)
        put("required", JsonArray(fields.keys.map(::JsonPrimitive)))
    }

    private fun array(item: JsonElement, minimum: Int? = null) = buildJsonObject {
        put("type", "array"); put("items", item); minimum?.let { put("minItems", it) }
    }

    private fun japaneseOnly(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { (key, child) ->
            if (key == "learningText") JsonObject(child.jsonObject + ("pattern" to JsonPrimitive(japanesePattern)))
            else japaneseOnly(child)
        })
        is JsonArray -> JsonArray(value.map(::japaneseOnly))
        else -> value
    }
}
