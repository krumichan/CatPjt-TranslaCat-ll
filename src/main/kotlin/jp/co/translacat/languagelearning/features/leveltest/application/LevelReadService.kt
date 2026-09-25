package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelInvalid
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelNotFound
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*

internal class LevelReadService(
    private val work: LevelTestUnitOfWork, private val audio: LevelAudioService, private val ai: LevelTestAi,
    private val context: LevelTestContextProvider,
) {
    suspend fun reference(userId: Long, itemId: Long): LevelAudioBytes {
        val item = ownedItem(userId, itemId, false).second
        return audio.load(item.data.referenceAudio?.objectKey ?: levelNotFound())
    }

    suspend fun answer(userId: Long, itemId: Long): LevelAudioBytes {
        ownedItem(userId, itemId, true)
        val key = work.write(userId) { records.submission(itemId)?.audioKey } ?: levelNotFound()
        return audio.load(key)
    }

    suspend fun model(userId: Long, itemId: Long): LevelAudioBytes {
        val (session, item) = ownedItem(userId, itemId, true)
        if (item.data.itemType == LevelTestItemType.SPEAKING_REPEAT) return reference(userId, itemId)
        if (item.data.itemType !in setOf(
                LevelTestItemType.SPEAKING_GUIDED_RESPONSE, LevelTestItemType.SPEAKING_SHORT_RESPONSE,
            )
        ) levelInvalid("모범 답안 음성을 제공하지 않는 문항입니다.")
        item.modelAnswerAudioKey?.let { return audio.load(it) }
        val text = work.write(userId) {
            records.submission(itemId)
                ?.let {
                    records.evaluation(
                        it.id,
                    )?.data?.recommendedAnswers?.firstOrNull { answer -> answer.isNotBlank() }
                }
        }
            ?: levelInvalid("모범 답안이 없습니다.")
        val stored = audio.storeModel(userId, ai.synthesize(session, item, text, context.current(userId)))
        val key = try {
            work.write(userId) {
                val latest = records.item(itemId) ?: levelNotFound()
                latest.modelAnswerAudioKey ?: stored.key.also {
                    records.saveItem(
                        latest.copy(modelAnswerAudioKey = it),
                    )
                }
            }
        } catch (failure: Throwable) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                runCatching {
                    audio.abandon(
                        stored.key,
                    )
                }
            }
            throw failure
        }
        if (key != stored.key) audio.abandon(stored.key)
        return audio.load(key)
    }

    suspend fun healthy(key: String?): Boolean = key != null && audio.healthy(key)
    private suspend fun ownedItem(userId: Long, itemId: Long, completed: Boolean): Pair<LevelSession, LevelItem> =
        work.write(userId) {
            val item = records.item(itemId) ?: levelNotFound()
            val session = LevelSessionService.owned(records, userId, item.sessionId)
            if (completed && session.status != LevelTestSessionStatus.COMPLETED) levelNotFound()
            session to item
        }
}
