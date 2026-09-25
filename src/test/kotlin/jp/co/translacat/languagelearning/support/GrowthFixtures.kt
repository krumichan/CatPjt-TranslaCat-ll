package jp.co.translacat.languagelearning.support

import jp.co.translacat.languagelearning.features.growth.api.GrowthWire
import kotlinx.serialization.json.*

internal object GrowthFixtures {
    const val SOURCE = "1ae93ac7-179b-4700-9edb-eb001461f033"
    fun envelope(): JsonObject = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/contracts/growth-v1-envelope.json")).bufferedReader()
            .use { it.readText() },
    ).jsonObject

    fun selectedEnvelope(
        sequence: Long = 1, eventId: String = "5ab909cb-f6ce-4b83-83c8-b172102164eb", userId: Long = 123,
    ): JsonObject {
        val operation =
            Json.parseToJsonElement(envelope().getValue("payloadJson").jsonPrimitive.content).jsonObject.getValue(
                "operations",
            ).jsonArray[3]
        val payload = buildJsonObject { put("operations", JsonArray(listOf(operation))) }.toString()
        return JsonObject(
            envelope() + mapOf(
                "sequence" to JsonPrimitive(sequence), "eventId" to JsonPrimitive(eventId),
                "userId" to JsonPrimitive(userId), "payloadJson" to JsonPrimitive(payload),
                "payloadSha256" to JsonPrimitive(GrowthWire.hash(payload)),
            ),
        )
    }
}
