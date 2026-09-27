package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingEvaluationJob
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingSetStatus
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingEvaluationResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.LocalDate
import java.util.*

/** 모델 호출은 claim과 publish 사이에서 실행하며, 평가·Growth·완료 상태는 함께 커밋한다. */
internal class WritingEvaluationState(private val work: WritingSetUnitOfWork) {
    internal data class Claim(val userId: Long, val answerId: Long, val token: String)

    suspend fun recoverable(limit: Int = 20): List<WritingEvaluationJob> = work.read {
        evaluations.recoverable(nowUtc, limit)
    }

    suspend fun claim(userId: Long, answerId: Long, token: String = UUID.randomUUID().toString()): Claim? {
        require(UUID.fromString(token).toString() == token)
        return work.write(userId) {
            if (answers.findById(userId, answerId) == null) return@write null
            if (evaluations.claim(userId, answerId, token, nowUtc, nowUtc.plus(Duration.ofMinutes(20))))
                Claim(userId, answerId, token) else null
        }
    }

    suspend fun fail(claim: Claim, code: String): Boolean {
        require(Regex("[A-Z][A-Z0-9_]{0,79}").matches(code))
        return work.write(claim.userId) {
            evaluations.fail(claim.userId, claim.answerId, claim.token, code, nowUtc)
        }
    }

    suspend fun context(
        claim: Claim,
        originLanguage: String,
        learningLanguage: String,
        learningDate: LocalDate,
    ): WritingEvaluationContext = work.read {
        val answer = answers.findById(claim.userId, claim.answerId) ?: error("WRITING_ANSWER_NOT_FOUND")
        val item = items.find(claim.userId, answer.itemId) ?: error("WRITING_ITEM_NOT_FOUND")
        val set = sets.findById(claim.userId, item.setId) ?: error("WRITING_SET_NOT_FOUND")
        WritingEvaluationContextBuilder.build(
            "daily-eval-${answer.id}-$learningDate", set, item, answer,
            originLanguage, learningLanguage, learningDate,
        )
    }

    suspend fun publish(
        claim: Claim,
        result: WritingEvaluationResult,
        learningDate: LocalDate,
        canonicalKeys: List<String>,
    ): Boolean = work.write(claim.userId) {
        val answer = answers.findById(claim.userId, claim.answerId) ?: return@write false
        val item = items.find(claim.userId, answer.itemId) ?: return@write false
        val set = sets.findById(claim.userId, item.setId) ?: return@write false
        if (!evaluations.succeed(claim.userId, claim.answerId, claim.token, result, nowUtc)) return@write false
        val profile = growth.profile(claim.userId)
        require(profile != null && profile.state != "LEVEL_TEST_REQUIRED") { "LEVEL_TEST_REQUIRED" }
        val scores = result.scores
        val signals = result.payload.getValue("profileSignals") as JsonObject
        fun values(key: String): List<String> = ((signals[key] as? JsonArray) ?: JsonArray(emptyList()))
            .map { it.jsonPrimitive.content }.filter(String::isNotBlank)
        GrowthProjector(growth).apply(
            claim.userId,
            GrowthChange.WritingScored(
                learningDate = learningDate, difficulty = item.difficulty.name,
                scores = listOf(
                    scores.meaning, scores.grammar, scores.vocabulary, scores.naturalness, scores.expression,
                )
                    .map(Int::toDouble),
                signals = mapOf(
                    "STRENGTH" to values("strengthTags"), "WEAKNESS" to values("weaknessTags"),
                    "GRAMMAR_WEAKNESS" to values("grammarPatterns"),
                    "ERROR_PATTERN" to (values("vocabularyPatterns") + values("naturalnessPatterns") +
                        values("expressionPatterns") + values("meaningPatterns")),
                    "RECOMMENDED_FOCUS" to values("recommendedFocus"),
                ),
                canonicalKeys = canonicalKeys,
            ),
            nowUtc,
        )
        if (set.status == WritingSetStatus.READY) {
            val all = items.list(claim.userId, set.id)
            if (all.size == set.sentenceCount && all.map { it.order } == (1..set.sentenceCount).toList() &&
                all.all { evaluations.hasSuccessfulForItem(claim.userId, it.id) }) {
                check(sets.complete(claim.userId, set.id, nowUtc))
            }
        }
        true
    }
}
