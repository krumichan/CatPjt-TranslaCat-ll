package jp.co.translacat.languagelearning.shared.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration

/** 완성된 업무 지시를 Python의 범용 실행 경계로 한 번만 보낸다. */
internal class HttpModelExecution(
    baseUrl: String,
    private val apiKey: String,
    private val clock: Clock = Clock.systemUTC(),
) : ModelExecutionPort, AutoCloseable {
    private val maxResponseBytes = 1_000_000
    private val base = URI.create(if (baseUrl.endsWith('/')) baseUrl else "$baseUrl/")
    private val target: URI
    private val http = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    init {
        require(
            base.scheme in setOf("http", "https") && base.host != null &&
                base.rawUserInfo == null && base.rawQuery == null && base.rawFragment == null,
        )
        require(apiKey.isNotBlank() && '\r' !in apiKey && '\n' !in apiKey)
        target = base.resolve("internal/v1/model/execute")
        require(target.scheme == base.scheme && target.host == base.host && target.port == base.port)
    }

    override suspend fun execute(command: ModelExecutionCommand): ModelExecutionResult {
        val remaining = Duration.between(clock.instant(), command.deadlineUtc).toMillis().coerceAtMost(300_000)
        if (remaining <= 0) throw ModelExecutionFailure("MODEL_DEADLINE_EXCEEDED", 504, false)
        val effort = when (command.tier) {
            ModelTier.LUNA -> "none"
            ModelTier.NANO, ModelTier.MINI -> "low"
            ModelTier.SOL -> "high"
        }
        val payload = buildJsonObject {
            put("traceId", command.traceId)
            put("instructions", command.instructions)
            put(
                "messages",
                JsonArray(
                    command.messages.map {
                        buildJsonObject { put("role", it.role); put("content", it.content) }
                    },
                ),
            )
            put("tier", command.tier.name)
            put("reasoningEffort", effort)
            put("verbosity", command.verbosity)
            put("maxOutputTokens", command.maxOutputTokens)
            put("remainingMilliseconds", remaining)
            put("maxProviderCalls", 1)
            put("responseSchema", command.responseSchema ?: JsonNull)
            put("schemaName", command.schemaName?.let(::JsonPrimitive) ?: JsonNull)
            put("strict", command.strict)
            put("taskName", command.taskName?.let(::JsonPrimitive) ?: JsonNull)
        }
        val request = HttpRequest.newBuilder(target)
            .timeout(Duration.ofMillis(remaining))
            .header("X-API-KEY", apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
            .build()
        val response = try {
            withTimeout(remaining) {
                runInterruptible(Dispatchers.IO) {
                    val received = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
                    received.statusCode() to received.body().use { it.readNBytes(maxResponseBytes + 1) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            throw ModelExecutionFailure("MODEL_DEADLINE_EXCEEDED", 504, false)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: HttpTimeoutException) {
            throw ModelExecutionFailure("MODEL_DEADLINE_EXCEEDED", 504, false)
        } catch (_: Exception) {
            throw ModelExecutionFailure("MODEL_EXECUTION_TRANSPORT", 503, true)
        }
        if (response.second.size > maxResponseBytes) throw ModelExecutionFailure("MODEL_RESPONSE_TOO_LARGE", 502, false)
        val body = try {
            Json.parseToJsonElement(response.second.toString(Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            throw ModelExecutionFailure("MODEL_EXECUTION_PROTOCOL", 502, false)
        }
        if (response.first !in 200..299) {
            val detail = body["detail"] as? JsonObject
            val code = (detail?.get("code") as? JsonPrimitive)?.contentOrNull
                ?.takeIf { Regex("[A-Z][A-Z0-9_]{0,79}").matches(it) }
                ?: "MODEL_EXECUTION_HTTP_ERROR"
            val retryable = (detail?.get("retryable") as? JsonPrimitive)?.booleanOrNull == true
            val origin = providerFailureOrigin(detail)
            throw ModelExecutionFailure(
                code, response.first, retryable, providerRetryAfter(detail), origin.first, origin.second,
                providerFailureSignal(detail),
            )
        }
        return try {
            require(body["providerCalls"]?.jsonPrimitive?.int == 1)
            val inputTokens = requireNotNull(body["inputTokens"]?.jsonPrimitive?.intOrNull)
            val outputTokens = requireNotNull(body["outputTokens"]?.jsonPrimitive?.intOrNull)
            val provider = requireNotNull(body["provider"]?.jsonPrimitive?.contentOrNull)
            val model = requireNotNull(body["model"]?.jsonPrimitive?.contentOrNull)
            require(inputTokens >= 0 && outputTokens >= 0 && provider.isNotBlank() && model.isNotBlank())
            ModelExecutionResult(requireNotNull(body["output"]), inputTokens, outputTokens, provider, model)
        } catch (_: Exception) {
            throw ModelExecutionFailure("MODEL_EXECUTION_PROTOCOL", 502, false)
        }
    }

    override fun close() {
        http.shutdownNow()
    }
}
