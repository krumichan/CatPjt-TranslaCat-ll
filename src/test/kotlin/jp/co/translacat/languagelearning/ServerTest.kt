package jp.co.translacat.languagelearning

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ServerTest {

    @Test
    fun `health endpoint returns service status`() = testApplication {
        configure(
            "application.yaml",
            "application-test.yaml",
        )

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)

        val body = Json
            .parseToJsonElement(response.bodyAsText())
            .jsonObject

        assertEquals(
            "UP",
            body["status"]?.jsonPrimitive?.content,
        )

        assertEquals(
            "translacat-language-learning",
            body["service"]?.jsonPrimitive?.content,
        )
    }
}