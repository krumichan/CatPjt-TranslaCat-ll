package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelInvalid
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelItem
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelPoolQuestion
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelSession
import jp.co.translacat.languagelearning.features.leveltest.domain.model.LevelTestSessionType
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelQuestionPolicy
import jp.co.translacat.languagelearning.features.leveltest.domain.policy.LevelTestRules
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.util.*

/** 관리자 정책이 켜진 경우에만 한 번에 한 문항을 보충한다. 네트워크 동안 DB 연결/행 잠금을 보유하지 않는다. */
internal class LevelPoolMaintenance(
    private val work: LevelTestUnitOfWork,
    private val context: LevelTestContextProvider,
    private val ai: LevelTestAi,
    private val audio: LevelAudioService,
    private val leaseSeconds: Long = 210,
) {
    suspend fun refillOnce() {
        val policy = context.poolPolicy()
        if (!policy.first) return
        val pairs = context.activeLanguagePairs()
        if (pairs.isEmpty()) return
        val token = UUID.randomUUID().toString()
        if (!work.write(null) { records.claimMaintenance(token, nowUtc, nowUtc.plusSeconds(leaseSeconds)) }) return
        var upload: LevelAudioUpload? = null
        var retained = false
        try {
            for ((origin, learning) in pairs) {
                val pool = work.read { records.poolQuestions(origin, learning).filter { it.active } }
                val counts = pool.groupingBy { it.data.itemType to it.data.complexityBand }.eachCount()
                val bucket =
                    LevelTestRules.poolTargets(policy.second).entries.firstOrNull { (counts[it.key] ?: 0) < it.value }
                        ?: continue
                val number = LevelTestRules.recipe.first { it.itemType == bucket.key.first }.questionNumber
                val now = work.read { nowUtc }
                val session = LevelSession(
                    uid = "pool-$token", userId = 0, sessionType = LevelTestSessionType.INITIAL,
                    originLanguage = origin, learningLanguage = learning, timezone = "UTC",
                    currentQuestionNumber = number,
                    currentComplexityBand = bucket.key.second, startedAt = now, lastActivityAt = now,
                    idempotencyKey = token,
                )
                val recent = pool.takeLast(100)
                    .map {
                        LevelItem(
                            id = it.id, sessionId = 0, questionNumber = it.data.questionNumber, data = it.data,
                            createdAt = it.createdAt,
                        )
                    }
                val generation = LevelGenerationContext(
                    session, number, bucket.key.second, "lt:pool:$token", emptyList(), emptyList(), recent,
                    LevelTestRules.scenarios(
                        token.hashCode().toLong(), number, emptyList(),
                        recent.map { it.data.diversityMetadata.scenarioCategory },
                    ),
                    now,
                )
                if (LevelTestRules.requiresAudio(bucket.key.first)) upload = audio.reserveReference(null)
                val data = ai.generate(generation, upload)
                LevelQuestionPolicy.validate(data, session, number, bucket.key.second, generation.scenarios)
                upload?.let { audio.verify(checkNotNull(data.referenceAudio), it) }
                val saved = work.write(null) {
                    if (!records.ownsMaintenance(token, nowUtc)) levelInvalid("문제풀 보충 lease가 만료되었습니다.")
                    records.savePool(
                        LevelPoolQuestion(
                            originLanguage = origin, learningLanguage = learning, data = data, createdAt = nowUtc,
                        ),
                    )
                }
                retained = upload == null || saved.data.referenceAudio?.objectKey == upload.objectKey
                return
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { work.write(null) { records.releaseMaintenance(token) } }
                if (!retained) upload?.let { runCatching { audio.abandon(it.objectKey) } }
            }
        }
    }
}
