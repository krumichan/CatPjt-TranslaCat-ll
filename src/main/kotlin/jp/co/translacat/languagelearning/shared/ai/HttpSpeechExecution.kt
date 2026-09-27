package jp.co.translacat.languagelearning.shared.ai

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.util.*

/** 오디오 접근권한·저장은 LL에 두고 Python의 기술적 음성 실행만 호출한다. */
internal class HttpSpeechExecution(
    baseUrl: String,
    private val apiKey: String,
    private val clock: Clock = Clock.systemUTC(),
) : SpeechExecutionPort, AutoCloseable {
    private val target: URI
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    init {
        val base = URI.create(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
        require(
            base.scheme in setOf("http", "https") && base.host != null &&
                base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null,
        )
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey)
        target = base.resolve("internal/v1/speech/synthesize")
    }

    override suspend fun synthesize(command: SpeechSynthesisCommand): SpeechSynthesisResult {
        // 상위 업무의 남은 deadline을 전달하며 자동 재시도로 호출 예산을 늘리지 않는다.
        val remaining = Duration.between(clock.instant(), command.deadlineUtc).toMillis().coerceAtMost(300_000)
        if (remaining <= 0) throw ModelExecutionFailure("SPEECH_DEADLINE_EXCEEDED", 504, false)
        val payload = buildJsonObject {
            put("requestId", command.requestId)
            put("text", command.text)
            put("voice", command.voice)
            put("language", command.language)
            put("speed", command.speed)
            put("remainingMilliseconds", remaining)
            put("maxProviderCalls", 1)
        }
        val request = HttpRequest.newBuilder(target)
            .timeout(Duration.ofMillis(remaining))
            .header("X-API-KEY", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
        val limit = 27_000_000
        val response = try {
            withTimeout(remaining) {
                runInterruptible(Dispatchers.IO) {
                    val received = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    received.statusCode() to received.body().use { it.readNBytes(limit + 1) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw ModelExecutionFailure("SPEECH_DEADLINE_EXCEEDED", 504, false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: HttpTimeoutException) {
            throw ModelExecutionFailure("SPEECH_DEADLINE_EXCEEDED", 504, false)
        } catch (_: Exception) {
            throw ModelExecutionFailure("SPEECH_EXECUTION_TRANSPORT", 503, true)
        }

        // 원문 오류를 노출하지 않고 기술 오류와 잘못된 응답을 구분한다.
        if (response.second.size > limit) throw ModelExecutionFailure("SPEECH_RESPONSE_TOO_LARGE", 502, false)
        val body = try {
            Json.parseToJsonElement(response.second.toString(Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            throw ModelExecutionFailure("SPEECH_EXECUTION_PROTOCOL", 502, false)
        }
        if (response.first !in 200..299) {
            val detail = body["detail"] as? JsonObject
            val code = (detail?.get("code") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { Regex("[A-Z][A-Z0-9_]{0,79}").matches(it) } ?: "SPEECH_EXECUTION_HTTP_ERROR"
            val origin = providerFailureOrigin(detail)
            throw ModelExecutionFailure(
                code, response.first,
                (detail?.get("retryable") as? JsonPrimitive)?.booleanOrNull == true,
                providerRetryAfter(detail), origin.first, origin.second, providerFailureSignal(detail),
            )
        }
        return try {
            require(body.getValue("providerCalls").jsonPrimitive.int == 1)
            val bytes = Base64.getDecoder().decode(body.getValue("audioBase64").jsonPrimitive.content)
            val contentType = body.getValue("contentType").jsonPrimitive.content
            val provider = body.getValue("provider").jsonPrimitive.content
            val model = body.getValue("model").jsonPrimitive.content
            val durationValue = body.getValue("durationSeconds")
            val duration = if (durationValue is JsonNull) null else durationValue.jsonPrimitive.double
            require(bytes.isNotEmpty() && bytes.size <= 20_000_000)
            require(contentType in setOf("audio/wav", "audio/x-wav", "audio/mpeg", "audio/ogg"))
            require(provider.isNotBlank() && model.isNotBlank())
            require(duration == null || duration.isFinite() && duration >= 0)
            SpeechSynthesisResult(bytes, contentType, provider, model, duration)
        } catch (_: Exception) {
            throw ModelExecutionFailure("SPEECH_EXECUTION_PROTOCOL", 502, false)
        }
    }

    override fun close() {
        http.shutdownNow()
    }
}
