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
import java.time.Instant
import java.util.*

/** Python 기술적 오디오 처리만 호출하며 학습 합격 기준을 전달하거나 위임하지 않는다. */
internal class HttpSpeechTranscription(
    baseUrl: String, private val apiKey: String,
    private val clock: Clock = Clock.systemUTC(),
) : SpeechTranscriptionPort, AutoCloseable {
    private val base: URI = URI.create(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    init {
        require(
            base.scheme in setOf(
                "http", "https",
            ) && base.host != null && base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null,
        )
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey)
    }

    override suspend fun normalize(command: AudioDecodeCommand): AudioDecodeResult {
        val body = send("normalize", command.audioBytes, command.requestId, command.deadlineUtc, emptyMap())
        return parse {
            val bytes = Base64.getDecoder().decode(body.getValue("audioBase64").jsonPrimitive.content)
            require(bytes.isNotEmpty() && bytes.size <= 20_000_000)
            AudioDecodeResult(
                bytes, body.number("durationSeconds"), body.getValue("sourceFormat").jsonPrimitive.content,
                body.number("rms"), body.number("peak"), body.number("silenceRatio").also { require(it in 0.0..1.0) },
                body.getValue("sampleRate").jsonPrimitive.int.also { require(it == 16000) },
                body.getValue("channels").jsonPrimitive.int.also { require(it == 1) },
            )
        }
    }

    override suspend fun hasSpeech(command: AudioDecodeCommand): Boolean {
        val body = send("evidence", command.audioBytes, command.requestId, command.deadlineUtc, emptyMap())
        return parse {
            val value = body.getValue("hasSpeech").jsonPrimitive
            require(!value.isString)
            value.boolean
        }
    }

    override suspend fun transcribe(command: SpeechTranscriptionCommand): SpeechTranscriptionResult {
        // 명시된 기술 옵션과 남은 전체 deadline을 한 번의 추론에 전달한다.
        val options = buildJsonObject {
            put("runtime", command.runtime)
            put("language", command.language?.let(::JsonPrimitive) ?: JsonNull)
            put("initialPrompt", command.initialPrompt?.let(::JsonPrimitive) ?: JsonNull)
            put("beamSize", command.beamSize)
            put("vadFilter", command.vadFilter)
            put("minSilenceDurationMs", command.minSilenceDurationMs)
            put("conditionOnPreviousText", command.conditionOnPreviousText)
            put("maxProviderCalls", 1)
        }
        val body = send("transcribe", command.audioBytes, command.requestId, command.deadlineUtc, options)
        return parse {
            require(body.getValue("providerCalls").jsonPrimitive.int == 1)
            SpeechTranscriptionResult(
                body.getValue("text").jsonPrimitive.content, body.optionalString("language"),
                body.optionalNumber("languageProbability")?.also { require(it in 0.0..1.0) },
                body.optionalNumber("durationSeconds"),
                body.getValue("segments").jsonArray.map { item ->
                    val segment = item.jsonObject
                    val start = segment.number("startSeconds")
                    val end = segment.number("endSeconds").also { require(it >= start) }
                    SpeechTranscriptionSegment(
                        start, end, segment.getValue("text").jsonPrimitive.content,
                        segment.getValue("avgLogprob").jsonPrimitive.double.also { require(it.isFinite()) },
                        segment.optionalNumber("noSpeechProbability"),
                    )
                },
                body.getValue("provider").jsonPrimitive.content, body.getValue("model").jsonPrimitive.content,
                body.optionalString("modelVersion"),
            )
        }
    }

    private suspend fun send(
        path: String, bytes: ByteArray, requestId: String, deadline: Instant, options: Map<String, JsonElement>,
    ): JsonObject {
        require(bytes.isNotEmpty() && bytes.size <= 10 * 1024 * 1024)
        require(Regex("[A-Za-z0-9._:-]{1,100}").matches(requestId))
        val remaining = Duration.between(clock.instant(), deadline).toMillis().coerceAtMost(300_000)
        if (remaining <= 0) throw ModelExecutionFailure("SPEECH_DEADLINE_EXCEEDED", 504, false)
        val payload = buildJsonObject {
            put("requestId", requestId)
            put("audioBase64", Base64.getEncoder().encodeToString(bytes))
            put("remainingMilliseconds", remaining)
            options.forEach { (key, value) -> put(key, value) }
        }
        val request =
            HttpRequest.newBuilder(base.resolve("internal/v1/speech/$path")).timeout(Duration.ofMillis(remaining))
                .header("X-API-KEY", apiKey).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build()

        // 응답 크기·시간을 제한하고 원문 오류·녹음을 예외나 로그에 포함하지 않는다.
        val response = try {
            withTimeout(remaining) {
                runInterruptible(Dispatchers.IO) {
                    val received = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    received.statusCode() to received.body().use { it.readNBytes(27_000_001) }
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
        if (response.second.size > 27_000_000) throw ModelExecutionFailure("SPEECH_RESPONSE_TOO_LARGE", 502, false)
        val body = parse { Json.parseToJsonElement(response.second.toString(Charsets.UTF_8)).jsonObject }
        if (response.first !in 200..299) {
            val detail = body["detail"] as? JsonObject
            val code = (detail?.get("code") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { Regex("[A-Z][A-Z0-9_]{0,79}").matches(it) } ?: "SPEECH_EXECUTION_HTTP_ERROR"
            val origin = providerFailureOrigin(detail)
            throw ModelExecutionFailure(
                code, response.first, (detail?.get("retryable") as? JsonPrimitive)?.booleanOrNull == true,
                providerRetryAfter(detail), origin.first, origin.second, providerFailureSignal(detail),
            )
        }
        return body
    }

    private fun JsonObject.number(key: String) =
        getValue(key).jsonPrimitive.double.also { require(it.isFinite() && it >= 0) }

    private fun JsonObject.optionalNumber(key: String) = if (getValue(key) == JsonNull) null else number(key)
    private fun JsonObject.optionalString(key: String) =
        if (getValue(key) == JsonNull) null else getValue(key).jsonPrimitive.content

    private fun <T> parse(block: () -> T): T = try {
        block()
    } catch (_: Exception) {
        throw ModelExecutionFailure("SPEECH_EXECUTION_PROTOCOL", 502, false)
    }

    override fun close() {
        http.shutdownNow()
    }
}
