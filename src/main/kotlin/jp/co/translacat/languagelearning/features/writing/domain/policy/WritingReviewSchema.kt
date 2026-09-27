package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** 현재 Python Pydantic 계약을 LL 리소스와 문항별 근거 ID로 구성한다. */
internal object WritingReviewSchema {
    fun build(
        draft: WritingDraftEvidence,
        candidateId: String,
        contentHash: String,
        revisionHash: String? = null,
        recoveryHash: String? = null,
    ): JsonObject {
        require(candidateId.length in 1..100)
        require(Regex("[a-f0-9]{64}").matches(contentHash))
        require(revisionHash == null || Regex("[a-f0-9]{64}").matches(revisionHash))
        require(recoveryHash == null || Regex("[a-f0-9]{64}").matches(recoveryHash))
        require(revisionHash == null || recoveryHash == null)

        // 검증 종류에 맞는 기본 Schema를 고르고 응답의 후보 ID와 내용 hash를 고정한다.
        val resource = when {
            revisionHash != null -> "/writing/repaired-review-schema.json"
            recoveryHash != null -> "/writing/recovered-review-base-schema.json"
            else -> "/writing/task-review-schema.json"
        }
        val base = checkNotNull(javaClass.getResourceAsStream(resource))
            .bufferedReader().use { Json.parseToJsonElement(it.readText()) }
        val taskIds = draft.orderedSegmentIds.filterNot { it == "N1" }
        var schema = base
        schema = schema.putAt(listOf("properties", "candidateId", "enum"), enum(candidateId))
        schema = schema.putAt(listOf("properties", "contentHash", "enum"), enum(contentHash))
        schema = schema.putAt(listOf("properties", "difficultyEvidenceSegmentIds", "items", "enum"), enum(taskIds))
        schema = schema.putAt(
            listOf("\$defs", "CriterionCheck", "properties", "evidenceSegmentIds", "items", "enum"),
            enum(draft.orderedSegmentIds),
        )
        schema = schema.putAt(
            listOf("\$defs", "ProductionDemandCheck", "properties", "evidenceSegmentIds", "items", "enum"),
            enum(taskIds),
        )

        // 보정본과 원문 복구본의 추가 증거는 각각 별도 결합값으로 제한한다.
        if (recoveryHash != null) {
            schema = schema.putAt(listOf("properties", "recoveryHash", "enum"), enum(recoveryHash))
        }
        if (revisionHash != null) {
            schema = schema.putAt(listOf("properties", "revisionHash", "enum"), enum(revisionHash))
            schema = schema.putAt(
                listOf("properties", "productionDemandChecks"),
                buildJsonObject {
                    put("type", "array")
                    put("minItems", 3)
                    put("maxItems", 3)
                    put("items", buildJsonObject { put("\$ref", "#/\$defs/ProductionDemandCheck") })
                },
            )
            val preservation = schema.jsonObject.getValue("\$defs").jsonObject
                .getValue("RevisionPreservationCheck").jsonObject
            val variants = listOf("PASS", "FAIL", "UNSURE").map { status ->
                var properties: JsonElement = preservation.getValue("properties")
                properties = properties.putAt(listOf("status", "enum"), enum(status))
                properties =
                    properties.putAt(listOf("evidenceSegmentIds", "items", "enum"), enum(listOf("B0") + taskIds))
                properties = properties.putAt(
                    listOf("issues", if (status == "FAIL") "minItems" else "maxItems"),
                    JsonPrimitive(if (status == "FAIL") 1 else 0),
                )
                if (status != "UNSURE") {
                    properties = properties.putAt(listOf("evidenceSegmentIds", "minItems"), JsonPrimitive(2))
                }
                buildJsonObject {
                    put("type", "object")
                    put("properties", properties)
                    put("required", preservation.getValue("required"))
                    put("additionalProperties", false)
                }
            }
            schema = schema.putAt(
                listOf("\$defs", "RevisionPreservationCheck"),
                buildJsonObject { put("anyOf", JsonArray(variants)) },
            )
        }
        return schema.jsonObject
    }

    private fun enum(value: String): JsonArray = enum(listOf(value))
    private fun enum(values: List<String>): JsonArray = JsonArray(values.map(::JsonPrimitive))

    private fun JsonElement.putAt(path: List<String>, value: JsonElement): JsonElement {
        require(path.isNotEmpty())
        val objectValue = jsonObject
        val head = path.first()
        val next = if (path.size == 1) value else objectValue.getValue(head).putAt(path.drop(1), value)
        return JsonObject(objectValue + (head to next))
    }
}
