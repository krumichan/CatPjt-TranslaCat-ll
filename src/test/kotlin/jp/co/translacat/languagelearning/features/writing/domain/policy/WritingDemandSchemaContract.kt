package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** Python 원본 golden을 보존하고 승인된 demand 교차 조건 차이만 명시한다. */
internal object WritingDemandSchemaContract {
    private val rows = listOf(
        Triple("SCOPE_INTERACTION", "PRESENT", "RELATIONS_NOT_INTERDEPENDENT"),
        Triple("SCOPE_INTERACTION", "MISSING", null),
        Triple("SCOPE_INTERACTION", "UNSURE", null),
        Triple("PRECISE_STANCE", "PRESENT", "SCOPE_TOO_ROUTINE"),
        Triple("PRECISE_STANCE", "MISSING", null),
        Triple("PRECISE_STANCE", "UNSURE", null),
        Triple("COHERENT_REGISTER", "PRESENT", "REGISTER_NOT_INTEGRATED"),
        Triple("COHERENT_REGISTER", "MISSING", null),
        Triple("COHERENT_REGISTER", "UNSURE", null),
    )

    fun upgrade(original: JsonElement): JsonObject {
        val schema = original.jsonObject
        val definitions = schema.getValue("\$defs").jsonObject
        val demand = definitions.getValue("ProductionDemandCheck").jsonObject
        val baseProperties = demand.getValue("properties").jsonObject
        val variants = rows.map { (code, status, gap) ->
            val gapRule = if (gap == null) buildJsonObject { put("type", "null") } else buildJsonObject {
                put("anyOf", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "string")
                        put("enum", strings(gap))
                    })
                    add(buildJsonObject { put("type", "null") })
                })
            }
            buildJsonObject {
                put("type", "object")
                put("properties", JsonObject(baseProperties + mapOf(
                    "code" to JsonObject(baseProperties.getValue("code").jsonObject + ("enum" to strings(code))),
                    "status" to JsonObject(baseProperties.getValue("status").jsonObject + ("enum" to strings(status))),
                    "gapCode" to gapRule,
                    "evidenceSegmentIds" to JsonObject(baseProperties.getValue("evidenceSegmentIds").jsonObject +
                        ("minItems" to JsonPrimitive(if (status == "UNSURE") 0 else 1))),
                )))
                put("required", demand.getValue("required"))
                put("additionalProperties", false)
            }
        }
        return JsonObject(schema + ("\$defs" to JsonObject(definitions +
            ("ProductionDemandCheck" to buildJsonObject { put("anyOf", JsonArray(variants)) }))))
    }

    private fun strings(value: String) = JsonArray(listOf(JsonPrimitive(value)))
}
