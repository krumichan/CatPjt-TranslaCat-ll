package jp.co.translacat.languagelearning.features.practice.application

import jp.co.translacat.languagelearning.features.practice.domain.*
import java.time.Duration
import java.util.*

internal data class PracticeGenerationClaim(
    val set: PracticeSet, val token: String, val firstOrder: Int, val previous: List<PracticeQuestionContent>,
)

internal data class ReadingGeneratedBundle(val promptVersion: String, val questions: List<PracticeQuestionContent>)

internal class PracticeGenerationState(private val work: PracticeUnitOfWork) {
    suspend fun recoverable() = work.read { records.recoverable(nowUtc, 20) }

    suspend fun claim(userId: Long, setId: Long): PracticeGenerationClaim? = work.write(userId) {
        val set = records.find(userId, setId) ?: return@write null
        if (set.generationStatus !in setOf(PracticeGenerationStatus.PENDING, PracticeGenerationStatus.GENERATING) ||
            set.nextAttemptAt?.isAfter(nowUtc) == true || set.generationLeaseUntil?.isAfter(nowUtc) == true
        ) return@write null

        // 만료 lease도 기존 재시도 상한 안에서만 회수한다. 늦은 작업의 token은 새 claim에 의해 폐기된다.
        val recovered = set.generationStatus == PracticeGenerationStatus.GENERATING
        if (recovered && set.retryCount >= 3) {
            records.save(
                set.copy(
                    generationStatus = failureStatus(set.id), generationToken = null,
                    generationLeaseUntil = null, failureCode = "AI_GENERATION_FAILED",
                ),
            )
            return@write null
        }
        val previous = records.questions(setId)
        val token = UUID.randomUUID().toString()
        val claimed = records.save(
            set.copy(
                generationStatus = PracticeGenerationStatus.GENERATING,
                generationToken = token, generationLeaseUntil = nowUtc.plus(Duration.ofMinutes(30)),
                nextAttemptAt = null,
                retryCount = set.retryCount + if (recovered) 1 else 0,
            ),
        )
        PracticeGenerationClaim(
            claimed, token, PracticePolicy.firstMissing(previous, set.questionCount), previous.map { it.content },
        )
    }

    suspend fun publish(claim: PracticeGenerationClaim, bundle: ReadingGeneratedBundle): Boolean =
        work.write(claim.set.userId) {
            // 지문 전체가 검증된 다음 한 번에 공개한다. 재시작 뒤의 늦은 결과와 기존 문항 덮어쓰기는 거부한다.
            val set = records.find(claim.set.userId, claim.set.id) ?: return@write false
            if (set.generationToken != claim.token || set.generationStatus != PracticeGenerationStatus.GENERATING) return@write false
            val existing = records.questions(set.id)
            if (PracticePolicy.firstMissing(existing, set.questionCount) != claim.firstOrder) return@write false
            validateBundle(set, claim.firstOrder, bundle)
            bundle.questions.forEach { records.addQuestion(set.id, it) }
            val complete = claim.firstOrder + bundle.questions.size > set.questionCount
            records.save(
                set.copy(
                    generationStatus = if (complete) PracticeGenerationStatus.READY else PracticeGenerationStatus.PENDING,
                    generationToken = null, generationLeaseUntil = null, nextAttemptAt = null, retryCount = 0,
                    failureCode = null, promptVersion = bundle.promptVersion,
                ),
            )
            true
        }

    suspend fun fail(claim: PracticeGenerationClaim, code: String, retryable: Boolean) = work.write(claim.set.userId) {
        require(Regex("[A-Z][A-Z0-9_]{0,99}").matches(code))
        val set = records.find(claim.set.userId, claim.set.id) ?: return@write
        if (set.generationToken != claim.token) return@write
        if (retryable && set.retryCount < 3) {
            val delaySeconds = (10L shl set.retryCount).coerceAtMost(120)
            records.save(
                set.copy(
                    generationStatus = PracticeGenerationStatus.PENDING, generationToken = null,
                    generationLeaseUntil = null, nextAttemptAt = nowUtc.plusSeconds(delaySeconds),
                    retryCount = set.retryCount + 1,
                    failureCode = null,
                ),
            )
        } else {
            records.save(
                set.copy(
                    generationStatus = failureStatus(set.id), generationToken = null,
                    generationLeaseUntil = null, nextAttemptAt = null, failureCode = code,
                ),
            )
        }
    }

    suspend fun retry(userId: Long, setId: Long): PracticeSet = work.write(userId) {
        val set = records.find(userId, setId) ?: throw PracticeFailure("LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND", 400)
        PracticePolicy.requireNewLearning(set.domain, set.mode, set.complexityBand)
        if (set.generationStatus !in setOf(
                PracticeGenerationStatus.PARTIAL, PracticeGenerationStatus.FAILED,
            )
        ) return@write set
        records.save(
            set.copy(
                generationStatus = PracticeGenerationStatus.PENDING, generationToken = null,
                generationLeaseUntil = null, nextAttemptAt = null, retryCount = 0, failureCode = null,
            ),
        )
    }

    private fun PracticeTransaction.failureStatus(setId: Long) =
        if (records.questions(setId).isEmpty()) PracticeGenerationStatus.FAILED else PracticeGenerationStatus.PARTIAL

    private fun validateBundle(set: PracticeSet, firstOrder: Int, bundle: ReadingGeneratedBundle) {
        val count = when (firstOrder) {
            1 -> 3; 4 -> 2; else -> throw PracticeFailure("AI_SCHEMA_INVALID", 422)
        }
        val passageId = if (firstOrder == 1) "p1" else "p2"
        val slots = PracticePolicy.slots(set.mode, set.complexityBand)
        if (bundle.promptVersion.isBlank() || bundle.questions.size != count || bundle.questions.map { it.passageText }
                .distinct().size != 1) {
            throw PracticeFailure("AI_SCHEMA_INVALID", 422)
        }
        bundle.questions.forEachIndexed { index, question ->
            val slot = slots[firstOrder + index - 1]
            if (question.order != slot.globalOrder || question.difficulty != slot.difficulty || question.complexityBand != slot.complexityBand ||
                question.skillTag != slot.skillTag || question.passageId != passageId || question.passageText.isNullOrBlank() ||
                question.prompt.isBlank() || question.explanationOrigin.isBlank() || question.explanationLearning.isBlank() ||
                question.questionType != PracticeQuestionType.SINGLE_CHOICE || question.options.size != 4 ||
                question.options.any { it.key.isBlank() || it.text.isBlank() } || question.options.map { it.key }
                    .distinct().size != 4 ||
                question.options.map { PracticePolicy.normalize(it.text) }
                    .distinct().size != 4 || question.correctAnswer.size != 1 ||
                question.correctAnswer.single() !in question.options.map { it.key }
            ) throw PracticeFailure("AI_SCHEMA_INVALID", 422)
        }
    }
}
