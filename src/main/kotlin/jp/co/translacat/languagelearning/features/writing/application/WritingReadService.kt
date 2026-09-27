package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.writing.domain.model.*
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingItemRevision
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime

/** 기존 BE Writing 응답 필드의 의미를 LL 저장 상태에서 조립한다. */
internal class WritingReadService(
    private val work: WritingSetUnitOfWork,
    private val clock: Clock = Clock.systemUTC(),
) {
    private data class ItemDetail(val item: WritingItem, val attempts: List<WritingAttemptView>)
    private data class Detail(val set: WritingSet, val items: List<ItemDetail>)

    suspend fun byId(userId: Long, setId: Long, today: LocalDate, reviewDays: Int): JsonObject? {
        val detail = work.read {
            val set = sets.findById(userId, setId) ?: return@read null
            Detail(set, items.list(userId, set.id).map { ItemDetail(it, answers.history(userId, it.id)) })
        } ?: return null
        return response(detail, today, reviewDays)
    }

    suspend fun byDate(
        userId: Long, date: LocalDate, type: WritingType,
        today: LocalDate, reviewDays: Int,
    ): JsonObject? {
        val detail = work.read {
            val set = sets.find(userId, date, type) ?: return@read null
            Detail(set, items.list(userId, set.id).map { ItemDetail(it, answers.history(userId, it.id)) })
        } ?: return null
        return response(detail, today, reviewDays)
    }

    suspend fun answer(userId: Long, answerId: Long): JsonObject? = work.read {
        val saved = answers.findById(userId, answerId) ?: return@read null
        answers.history(userId, saved.itemId).firstOrNull { it.answer.id == answerId }?.result()
    }

    private fun response(detail: Detail, today: LocalDate, reviewDays: Int): JsonObject {
        // 날짜 제한과 활성 재생성 lease는 동일한 조회 시점의 세트에 적용한다.
        val set = detail.set
        val reviewAvailable = !today.isBefore(set.learningDate) &&
            !today.isAfter(set.learningDate.plusDays(reviewDays - 1L))
        val regenerating = set.status in setOf(WritingSetStatus.READY, WritingSetStatus.COMPLETED) &&
            set.generationToken != null && set.generationLeaseUntil?.isAfter(LocalDateTime.now(clock)) == true

        // 답변·평가의 원문은 소유 사용자에게만 반환하고 성공 평가만 상세 값으로 공개한다.
        return buildJsonObject {
            put("dailySetId", LearningPublicId.encode(set.id))
            put("learningDate", set.learningDate.toString())
            put("writingType", set.writingType.name)
            put("snapshotId", set.snapshotId)
            put("status", set.status.name)
            put("sentenceCount", set.sentenceCount)
            put("generatedItemCount", detail.items.size)
            put("generationFailureMessage", set.failureMessage?.let(::JsonPrimitive) ?: JsonNull)
            put("regenerationCount", set.regenerationCount)
            put("promptVersion", set.promptVersion?.let(::JsonPrimitive) ?: JsonNull)
            put("reviewAvailable", reviewAvailable)
            put("regenerating", regenerating)
            put(
                "items",
                JsonArray(
                    detail.items.map { value ->
                        val attempts = value.attempts
                        val todayAnswer = attempts.firstOrNull { it.answer.attemptDate == today }
                        val canSubmit = reviewAvailable && !regenerating &&
                            todayAnswer?.answer?.evaluationStatus !in setOf(
                            WritingEvaluationStatus.PENDING,
                            WritingEvaluationStatus.SUCCESS,
                        )
                        buildJsonObject {
                            put("itemId", LearningPublicId.encode(value.item.id))
                            put("order", value.item.order)
                            put("difficulty", value.item.difficulty.name)
                            put("originText", value.item.originText)
                            put("keywords", Json.parseToJsonElement(value.item.keywordsJson))
                            put("focusMetrics", Json.parseToJsonElement(value.item.focusMetricsJson))
                            put("focusReason", value.item.focusReason)
                            put("providedFacts", Json.parseToJsonElement(value.item.providedFactsJson ?: "[]"))
                            put("requiredIntents", Json.parseToJsonElement(value.item.requiredIntentsJson ?: "[]"))
                            put(
                                "responseConstraints",
                                Json.parseToJsonElement(value.item.responseConstraintsJson ?: "[]"),
                            )
                            put("answered", attempts.isNotEmpty())
                            put("answeredToday", todayAnswer != null)
                            put("canSubmit", canSubmit)
                            put("attempts", JsonArray(attempts.map { it.response() }))
                            put("contentRevision", WritingItemRevision.of(value.item))
                        }
                    },
                ),
            )
        }
    }

    private fun WritingAttemptView.response(): JsonObject = buildJsonObject {
        put("answerId", LearningPublicId.encode(answer.id))
        put("attemptDate", answer.attemptDate.toString())
        put("answer", answer.text)
        put("submittedAt", submittedAt.toString())
        put("evaluationStatus", answer.evaluationStatus.name)
        put("evaluationFailureMessage", failureMessage?.let(::JsonPrimitive) ?: JsonNull)
        put("evaluation", evaluation?.response() ?: JsonNull)
    }

    private fun WritingAttemptView.result(): JsonObject = buildJsonObject {
        put("answerId", LearningPublicId.encode(answer.id))
        put("itemId", LearningPublicId.encode(answer.itemId))
        put("attemptDate", answer.attemptDate.toString())
        put("evaluationStatus", answer.evaluationStatus.name)
        put("evaluationFailureMessage", failureMessage?.let(::JsonPrimitive) ?: JsonNull)
        put("evaluation", evaluation?.response() ?: JsonNull)
    }

    private fun WritingEvaluationView.response(): JsonObject = buildJsonObject {
        put("evaluationId", LearningPublicId.encode(id))
        put("context", context)
        put("overall", overall)
        put("meaning", meaning)
        put("grammar", grammar)
        put("vocabulary", vocabulary)
        put("naturalness", naturalness)
        put("expression", expression)
        put("strengths", Json.parseToJsonElement(strengthsJson))
        put("weaknesses", Json.parseToJsonElement(weaknessesJson))
        put("corrections", Json.parseToJsonElement(correctionsJson))
        put("recommendedAnswers", Json.parseToJsonElement(recommendedAnswersJson))
        put("explanation", Json.parseToJsonElement(explanationJson))
        put("evaluationRubricVersion", evaluationRubricVersion)
        put("scoringPolicyVersion", scoringPolicyVersion)
        put("promptVersion", promptVersion)
        put("evaluatedAt", evaluatedAt.toString())
    }
}
