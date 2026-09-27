package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningSetState
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class ListeningGenerationService(
    private val work: ListeningUnitOfWork, private val settings: SettingsServiceOperations,
) {
    suspend fun retryGeneration(userId: Long, setId: Long): ListeningSetState {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val set = set(userId, setId) ?: missing()
            if (hasActiveJob(userId, setId, "GENERATE")) return@write set
            if (set.status !in setOf("FAILED", "PARTIAL") || set.generationFailure == null) invalid()
            val missing =
                (1..set.targetItemCount).firstOrNull { index -> set.items.none { it.index == index } } ?: invalid()

            // 누락된 논리 문항만 기존 수동 상한 안에서 재개한다. 게시한 문항·답변은 건드리지 않는다.
            for (retry in 1..policy.manualRetryLimit) {
                val key = "listening:set:$setId:generate:item:$missing:manual:$retry"
                if (jobForKey(userId, key) != null) continue
                enqueue(
                    userId, setId, "GENERATE", key,
                    buildJsonObject {
                        put("logicalItemIndex", missing)
                        put("manualRetryAttempt", retry)
                        put("replacementSequence", 0)
                    },
                )
                return@write updateSet(
                    set.copy(
                        status = if (set.items.isEmpty()) "GENERATING" else "PARTIAL",
                        generationFailure = null, generationRetries = retry,
                    ),
                )
            }
            invalid()
        }
    }

    suspend fun retryTts(userId: Long, itemId: Long): ListeningSetState {
        val policy = settings.listeningPolicy()
        return work.write(userId) {
            val set = sets(userId).firstOrNull { it.items.any { item -> item.id == itemId } }
                ?: throw LearningBusinessException("LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND", "Listening 문항을 찾을 수 없습니다.")
            val item = set.items.single { it.id == itemId }
            if (item.status != "NOT_EVALUABLE" || item.ttsRetries >= policy.manualRetryLimit || item.errorCode in setOf(
                    "AUDIO_TOO_SHORT", "AUDIO_TOO_LONG",
                )
            ) invalid()

            // 원문은 고정하고 해당 TTS만 재시도한다. 길이 교정 실패는 기존 정책대로 수동 재시도하지 않는다.
            val retried = item.copy(status = "TTS_PENDING", ttsRetries = item.ttsRetries + 1, errorCode = null)
            enqueue(
                userId, set.id, "TTS", "listening:item:$itemId:tts:manual:${retried.ttsRetries}",
                buildJsonObject { put("itemId", itemId) },
            )
            updateSet(
                set.copy(
                    status = if (set.status == "COMPLETED") set.status else "PARTIAL",
                    items = set.items.map { if (it.id == itemId) retried else it },
                ),
            )
        }
    }

    private fun invalid(): Nothing =
        throw LearningBusinessException("LISTENING_INVALID_STATE", "Listening 수동 재시도를 실행할 수 없습니다.")

    private fun missing(): Nothing =
        throw LearningBusinessException("LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND", "Listening Daily Set을 찾을 수 없습니다.")
}
