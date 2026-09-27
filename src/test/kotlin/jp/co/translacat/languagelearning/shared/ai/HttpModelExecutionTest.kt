package jp.co.translacat.languagelearning.shared.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class HttpModelExecutionTest {
    private val now = Instant.parse("2026-09-26T04:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val schema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject { put("status", buildJsonObject { put("type", "string") }) })
        put("required", buildJsonArray { add("status") })
        put("additionalProperties", false)
    }

    @Test
    fun `완성된 지시와 Schema를 한 번 전송하고 실행 메타데이터를 검증한다`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/v1/model/execute") { exchange ->
            requests.incrementAndGet()
            assertEquals("synthetic-internal-key", exchange.requestHeaders.getFirst("X-API-KEY"))
            val payload =
                Json.parseToJsonElement(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
            assertEquals("Synthetic instruction", payload.getValue("instructions").jsonPrimitive.content)
            assertEquals("MINI", payload.getValue("tier").jsonPrimitive.content)
            assertEquals("low", payload.getValue("reasoningEffort").jsonPrimitive.content)
            assertEquals(1, payload.getValue("maxProviderCalls").jsonPrimitive.int)
            assertEquals(schema, payload.getValue("responseSchema"))
            assertEquals(
                "Synthetic input",
                payload.getValue("messages").jsonArray[0].jsonObject.getValue("content").jsonPrimitive.content,
            )
            val body =
                """{"output":{"status":"PASS"},"inputTokens":12,"outputTokens":3,"provider":"fake-sdk","model":"synthetic","providerCalls":1}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            HttpModelExecution(
                "http://127.0.0.1:${server.address.port}/", "synthetic-internal-key", clock,
            ).use { client ->
                val result = runBlocking { client.execute(command()) }
                assertEquals("PASS", result.output.jsonObject.getValue("status").jsonPrimitive.content)
                assertEquals(12, result.inputTokens)
                assertEquals(1, requests.get())
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `기한이 지난 요청은 전송하지 않고 오류 본문은 코드만 전달한다`() {
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/v1/model/execute") { exchange ->
            requests.incrementAndGet()
            val body =
                """{"detail":{"code":"REFUSAL","retryable":false,"message":"SENSITIVE_SYNTHETIC_MARKER"}}""".toByteArray()
            exchange.sendResponseHeaders(502, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            HttpModelExecution(
                "http://127.0.0.1:${server.address.port}/", "synthetic-internal-key", clock,
            ).use { client ->
                val expired = assertFailsWith<ModelExecutionFailure> {
                    runBlocking { client.execute(command().copy(deadlineUtc = now)) }
                }
                assertEquals("MODEL_DEADLINE_EXCEEDED", expired.code)
                assertEquals(0, requests.get())
                val refusal = assertFailsWith<ModelExecutionFailure> { runBlocking { client.execute(command()) } }
                assertEquals("REFUSAL", refusal.code)
                assertFalse(refusal.retryable)
                assertFalse(refusal.message.orEmpty().contains("SENSITIVE_SYNTHETIC_MARKER"))
                assertEquals(1, requests.get())
            }
        } finally {
            server.stop(0)
        }
    }

    private fun command() = ModelExecutionCommand(
        traceId = "synthetic-trace", instructions = "Synthetic instruction",
        messages = listOf(ModelMessage("user", "Synthetic input")),
        tier = ModelTier.MINI, maxOutputTokens = 2048,
        deadlineUtc = now.plusSeconds(30), responseSchema = schema,
        schemaName = "synthetic_answer", strict = true,
        taskName = "LANGUAGE_LEARNING_WRITING_TASK_VERIFICATION",
    )
}
