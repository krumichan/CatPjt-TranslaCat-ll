package jp.co.translacat.languagelearning.shared.ai

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.time.Instant

internal enum class ModelTier { NANO, LUNA, MINI, SOL }

internal data class ModelMessage(val role: String, val content: String) {
    init {
        require(role in setOf("user", "assistant") && content.isNotBlank())
    }
}

/** 남은 전체 deadline은 상위 LL 업무 파이프라인에서 한 번만 만든다. */
internal data class ModelExecutionCommand(
    val traceId: String,
    val instructions: String,
    val messages: List<ModelMessage>,
    val tier: ModelTier,
    val maxOutputTokens: Int,
    val deadlineUtc: Instant,
    val responseSchema: JsonObject? = null,
    val schemaName: String? = null,
    val strict: Boolean = false,
    val verbosity: String = "low",
    val taskName: String? = null,
) {
    init {
        require(Regex("[A-Za-z0-9._:-]{1,100}").matches(traceId))
        require(instructions.isNotBlank() && messages.isNotEmpty() && messages.size <= 30)
        require(maxOutputTokens in 1..8192)
        require(verbosity in setOf("low", "medium", "high"))
        require((responseSchema == null) == (schemaName == null))
        require(!strict || responseSchema != null)
    }
}

internal data class ModelExecutionResult(
    val output: JsonElement,
    val inputTokens: Int,
    val outputTokens: Int,
    val provider: String,
    val model: String,
)

internal class ModelExecutionFailure(
    val code: String,
    val status: Int,
    val retryable: Boolean,
    val retryAfterSeconds: Long? = null,
    val failureKind: String? = null,
    val providerStatus: Int? = null,
    val failureSignal: String? = null,
) : RuntimeException(code)

internal fun interface ModelExecutionPort {
    suspend fun execute(command: ModelExecutionCommand): ModelExecutionResult
}
