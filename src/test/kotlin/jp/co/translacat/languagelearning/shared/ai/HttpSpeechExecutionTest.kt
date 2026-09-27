package jp.co.translacat.languagelearning.shared.ai

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.net.InetSocketAddress
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class HttpSpeechExecutionTest {
    @Test
    fun `음성 HTTP는 완성된 발화와 남은 예산만 전달한다`() = runBlocking {
        // 준비
        val calls = AtomicInteger()
        var captured: JsonObject? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/internal/v1/speech/synthesize") { exchange ->
            calls.incrementAndGet()
            captured = Json.parseToJsonElement(exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)).jsonObject
            val body =
                """{"audioBase64":"AQID","contentType":"audio/wav","durationSeconds":1.0,"provider":"test","model":"test","providerCalls":1}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            HttpSpeechExecution("http://127.0.0.1:${server.address.port}", "synthetic-key").use { client ->
                // 실행
                val response = client.synthesize(command(Instant.now().plusSeconds(10)))

                // 검증
                assertContentEquals(byteArrayOf(1, 2, 3), response.audioBytes)
                assertEquals(1, calls.get())
                assertEquals("Synthetic speech", captured?.get("text")?.jsonPrimitive?.content)
                assertEquals(1, captured?.get("maxProviderCalls")?.jsonPrimitive?.int)
                assertTrue(checkNotNull(captured).getValue("remainingMilliseconds").jsonPrimitive.long in 1L..10_000L)
            }
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `만료된 deadline은 HTTP를 호출하지 않는다`() = runBlocking {
        // 준비
        HttpSpeechExecution("http://127.0.0.1:1", "synthetic-key").use { client ->
            // 실행
            val failure = assertFailsWith<ModelExecutionFailure> {
                client.synthesize(command(Instant.EPOCH))
            }

            // 검증
            assertEquals("SPEECH_DEADLINE_EXCEEDED", failure.code)
            assertEquals(false, failure.retryable)
        }
    }

    private fun command(deadline: Instant) = SpeechSynthesisCommand(
        "Synthetic speech", "marin", "en", "NORMAL", deadline, "synthetic-tts",
    )
}
