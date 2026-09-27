package jp.co.translacat.languagelearning.features.practice.application

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthActivity
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.practice.domain.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration

internal data class PracticeAnswerResult(
    val questionId: Long,
    val attemptNo: Int,
    val correct: Boolean,
    val official: Boolean,
    val setCompleted: Boolean,
    val officialScore: Double?,
    val content: PracticeQuestionContent,
)

internal class PracticeAnswerService(private val work: PracticeUnitOfWork) {
    suspend fun submit(userId: Long, questionId: Long, answer: List<String>): PracticeAnswerResult =
        work.write(userId) {
            // 문항 소유권과 답변 횟수를 잠금 안에서 확인해 중복 제출이 공식 시도를 두 번 만들지 않게 한다.
            val question = records.question(userId, questionId) ?: throw PracticeFailure(
                "LANGUAGE_LEARNING_DAILY_ITEM_NOT_FOUND", 400,
            )
            val set = records.find(userId, question.setId) ?: throw PracticeFailure(
                "LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND", 400,
            )
            val attempts = records.attempts(set.id)
            val previous = attempts.filter { it.questionId == questionId }
            val normalized = PracticePolicy.validateAnswer(question.content, answer, previous)
            val attempt = records.addAttempt(
                PracticeAttempt(
                    0, questionId, previous.size + 1,
                    normalized, normalized == question.content.correctAnswer, nowUtc,
                ),
            )
            val projector = GrowthProjector(growth)

            // 전용 Vocabulary mastery와 Reading 표현 후보는 첫 시도만 반영한다.
            if (attempt.official && set.domain == PracticeDomain.VOCABULARY) {
                val canonicalKey = question.content.canonicalKey
                if (!canonicalKey.isNullOrBlank()) {
                    val old = records.masteries(userId).firstOrNull { it.canonicalKey == canonicalKey }
                        ?: VocabularyMastery(userId, canonicalKey, question.content.targetExpression.orEmpty())
                    records.saveMastery(old.evaluated(attempt.correct))
                }
            } else if (attempt.official && !attempt.correct) {
                projector.apply(
                    userId,
                    GrowthChange.SignalsTouched("VOCABULARY_CANDIDATE", PracticePolicy.candidates(question.content)),
                    nowUtc,
                )
            }

            // 모든 실제 문항과 공식 첫 답변이 있어야 완료 점수·metric·Growth를 같은 커밋에 기록한다.
            var finalized = set
            val questions = records.questions(set.id)
            val allAttempts = attempts + attempt
            if (set.status != PracticeStatus.COMPLETED && PracticePolicy.firstMissing(
                    questions, set.questionCount,
                ) > set.questionCount &&
                allAttempts.count { it.official } >= set.questionCount
            ) {
                val score =
                    PracticePolicy.round(allAttempts.count { it.official && it.correct } * 100.0 / set.questionCount)
                finalized = records.save(
                    set.copy(status = PracticeStatus.COMPLETED, officialScore = score, completedAt = nowUtc),
                )
                val metrics = PracticePolicy.metrics(questions, allAttempts)
                records.saveMetrics(set.id, metrics)
                val metadata = buildJsonObject {
                    put("mode", set.mode)
                    put("questionCount", set.questionCount)
                    put("complexityBand", set.complexityBand)
                }
                projector.apply(
                    userId,
                    GrowthChange.ActivityRecorded(
                        GrowthActivity(
                            userId = userId, source = set.domain.name, referenceId = "ll-practice-${set.id}",
                            learningDate = set.learningDate,
                            title = (if (set.domain == PracticeDomain.READING) "Reading · " else "Vocabulary · ") + set.mode,
                            durationSeconds = Duration.between(set.startedAt, nowUtc).seconds.coerceAtLeast(0),
                            status = "EVALUATED", overallScore = score, evaluationConfidence = 1.0,
                            startedAt = set.startedAt, completedAt = nowUtc, metadataJson = metadata.toString(),
                            createdAt = nowUtc, updatedAt = nowUtc,
                        ),
                        null,
                    ),
                    nowUtc,
                )
                val weaknesses = metrics.filter { it.score < 60 }.map { "${set.domain}:${it.skillTag}" }
                projector.apply(userId, GrowthChange.SignalsTouched("WEAKNESS", weaknesses), nowUtc)
                projector.apply(
                    userId,
                    GrowthChange.SignalsTouched(
                        "STRENGTH",
                        metrics.filter { it.score >= 85 }.map { "${set.domain}:${it.skillTag}" },
                    ),
                    nowUtc,
                )
                if (weaknesses.isNotEmpty()) {
                    projector.apply(
                        userId, GrowthChange.SignalsTouched("RECOMMENDED_FOCUS", listOf("${set.domain}:${set.mode}")),
                        nowUtc,
                    )
                }
            }
            PracticeAnswerResult(
                questionId, attempt.attemptNo, attempt.correct, attempt.official,
                finalized.status == PracticeStatus.COMPLETED, finalized.officialScore, question.content,
            )
        }
}
