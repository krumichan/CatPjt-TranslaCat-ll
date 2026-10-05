package jp.co.translacat.languagelearning.features.writing.application

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthProfile
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.keyword.domain.policy.KeywordSelectionPolicy
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy
import jp.co.translacat.languagelearning.features.writing.domain.model.NewWritingSet
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingRecentScore
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingSet
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.WritingScoring
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlinx.serialization.json.*
import java.util.*

/** 인증된 사용자 ID와 모드만 받아 LL 설정·키워드·성장·평가 이력으로 최초 snapshot을 만든다. */
internal class WritingContextService(
    private val settings: SettingsServiceOperations,
    private val keywords: KeywordOperations,
    private val work: WritingSetUnitOfWork,
    private val learningFacts: KeywordLearningFacts,
    private val snapshotId: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun getOrCreate(userId: Long, type: WritingType): WritingSet {
        // 현재 설정의 시간대로 학습일을 정하고 기존과 같은 설정·레벨 테스트 진입 조건을 검사한다.
        val context = settings.userSnapshot(userId)
        val user = context.result.settings
        UserSettingsPolicy.requireConfigured(user)
        val admin = settings.adminPolicy()
        if (!admin.adaptiveWritingEnabled) throw LearningBusinessException(
            UserSettingsPolicy.INVALID, "Adaptive Writing이 비활성화되어 있습니다.",
        )
        val date = context.learningDate
        val existing = work.write(userId) {
            prepareProfile(userId, date)
            sets.find(userId, date, type)
        }
        if (existing != null) return existing

        // 기존 Writing·Speaking 학습 여부에 따라 최초 즉시 적용과 예약 적용을 구분한다.
        // 공식 평가 전이라도 공개된 Speaking 세션은 원본의 학습 시작에 해당한다.
        val started = learningFacts.hasStartedLearning(userId)
        val candidates = if (admin.dailyKeywordMaxCount > 0) keywords.candidates(userId, started, date)
        else emptyList()
        val count = user.dailySentenceCount.coerceIn(admin.minDailySentenceCount, admin.maxDailySentenceCount)
        return work.write(userId) {
            // 사용자 행 잠금 아래 재확인하여 중복 요청이 선택 횟수나 snapshot을 두 번 만들지 않게 한다.
            sets.find(userId, date, type)?.let { return@write it }
            val profile = prepareProfile(userId, date)
            val selected = KeywordSelectionPolicy.select(userId, date, candidates, admin.dailyKeywordMaxCount) {
                growth.mastery(userId, it)
            }
            if (selected.isNotEmpty()) GrowthProjector(growth).apply(
                userId,
                GrowthChange.KeywordsSelected(date, selected.map { it.canonicalKey }), nowUtc,
            )
            val id = snapshotId()
            val summary = profileSummary(userId, profile)
            val request = buildJsonObject {
                put("requestId", "daily-generate-$id")
                put("originLanguage", checkNotNull(user.originLanguage))
                put("learningLanguage", checkNotNull(user.learningLanguage))
                put("writingType", type.name)
                put("sentenceCount", count)
                put("difficultyDistribution", distribution(count))
                put(
                    "selectedKeywords",
                    JsonArray(
                        selected.map { selection ->
                            buildJsonObject {
                                put("key", selection.candidate.key)
                                put("text", selection.candidate.text)
                                put("source", selection.candidate.source.name)
                                put("type", selection.candidate.type.name)
                                put("canonicalKey", selection.canonicalKey)
                                put("selectionWeight", selection.weight)
                            }
                        },
                    ),
                )
                put("learningProfile", summary)
                put("recentEvaluationSummary", recentSummary(userId, checkNotNull(user.learningLanguage), type))
                // 기존 누적 signal은 언어 provenance가 없어 현재 학습 언어의 실수로 단정하지 않는다.
                put("recentMistakes", JsonArray(emptyList()))
                // 추천 초점은 관찰된 학습 표현이 아니다. 실제 표현 근거가 없으면 비워 둔다.
                put("recentlyLearnedExpressions", JsonArray(emptyList()))
                put("generationDate", date.toString())
                put("snapshotId", id)
                put(
                    "languageComplexity",
                    buildJsonObject {
                        put("baseLevelScore", profile.baseLevelScore.asJson())
                        put("baseComplexityBand", baseBand(profile.baseLevelScore))
                        put("targetComplexityBand", JsonNull)
                        put("policyVersion", "language-complexity")
                    },
                )
                put("diversityContext", fingerprints.context(userId, checkNotNull(user.learningLanguage), nowUtc))
                put("contentDiversityPolicyVersion", "language-learning-diversity")
            }
            sets.create(NewWritingSet(userId, date, type, id, count, request.toString()), nowUtc)
        }
    }

    private fun WritingSetTransaction.prepareProfile(userId: Long, date: java.time.LocalDate): GrowthProfile {
        val profile = growth.profile(userId)
        if (profile == null || profile.state == "LEVEL_TEST_REQUIRED") throw LearningBusinessException(
            "LEVEL_TEST_REQUIRED", "최초 Level Test가 필요합니다.",
        )
        GrowthProjector(growth).apply(userId, GrowthChange.LearningPrepared(date), nowUtc)
        return checkNotNull(growth.profile(userId))
    }

    private fun WritingSetTransaction.profileSummary(userId: Long, profile: GrowthProfile): JsonObject {
        val scores = listOf(
            profile.meaningScore, profile.grammarScore, profile.vocabularyScore,
            profile.naturalnessScore, profile.expressionScore,
        )
        return buildJsonObject {
            // 현재 user 단위 성장 snapshot은 언어 귀속이 없어 생성기에서 성취 근거로 쓰지 않는다.
            put("languageScope", "UNKNOWN")
            put("learningLanguage", JsonNull)
            put("profileVersion", profile.profileVersion)
            put("baseLevelScore", profile.baseLevelScore.asJson())
            put("skillScores", if (scores.all { it == null }) JsonNull else skills(scores))
            put("grammarWeaknesses", signalKeys(userId, "GRAMMAR_WEAKNESS"))
            put(
                "keywordMasteries",
                JsonArray(
                    growth.masteries(userId, null, 30).map { mastery ->
                        buildJsonObject {
                            put("canonicalKey", mastery.canonicalKey)
                            put("score", mastery.score)
                        }
                    },
                ),
            )
            put(
                "difficultyPerformance",
                buildJsonObject {
                    put("review", profile.reviewPerformance.asJson())
                    put("normal", profile.normalPerformance.asJson())
                    put("challenge", profile.challengePerformance.asJson())
                },
            )
            put("errorPatterns", signalKeys(userId, "ERROR_PATTERN"))
            put("trend", profile.trend)
            put("confidence", profile.confidence)
            put("strengths", signalKeys(userId, "STRENGTH"))
            put("weaknesses", signalKeys(userId, "WEAKNESS"))
            put("recommendedFocus", signalKeys(userId, "RECOMMENDED_FOCUS"))
            put("additionalSignals", Json.parseToJsonElement(profile.additionalSignalsJson).jsonObject)
        }
    }

    private fun WritingSetTransaction.recentSummary(
        userId: Long, learningLanguage: String, type: WritingType,
    ): JsonObject {
        // 현재 언어·유형·평가 정책이 같은 저장 결과만 문맥으로 사용한다.
        val scores = evaluations.recentDailyScores(userId, learningLanguage, type)
        fun average(value: (WritingRecentScore) -> Int?): Double? {
            val values = scores.mapNotNull(value)
            return if (values.isEmpty()) null else Math.round(values.average() * 100.0) / 100.0
        }
        return buildJsonObject {
            put("learningLanguage", learningLanguage)
            put("writingType", type.name)
            put("scoringPolicyVersion", WritingScoring.policyVersion)
            put("evaluationRubricVersion", WritingScoring.rubricVersion)
            put("sampleCount", scores.size)
            put("overallAverage", average { it.overall }.asJson())
            put(
                "skillScores",
                if (scores.isEmpty()) JsonNull else skills(
                    listOf(
                        average { it.meaning }, average { it.grammar }, average { it.vocabulary },
                        average { it.naturalness }, average { it.expression },
                    ),
                ),
            )
            put("recentFocus", JsonArray(emptyList()))
        }
    }

    private fun WritingSetTransaction.signalKeys(userId: Long, type: String): JsonArray =
        JsonArray(growth.signals(userId, type, 10).map { JsonPrimitive(it.key) })

    private fun skills(values: List<Double?>): JsonObject = buildJsonObject {
        listOf("meaning", "grammar", "vocabulary", "naturalness", "expression").zip(values).forEach { (key, value) ->
            put(key, value.asJson())
        }
    }

    private fun distribution(count: Int): JsonObject {
        val review = if (count < 3) 0 else maxOf(1, Math.round(count * 0.20).toInt())
        val challenge = if (count < 3) 0 else maxOf(1, Math.round(count * 0.20).toInt())
        return buildJsonObject {
            put("review", review)
            put("normal", count - review - challenge)
            put("challenge", challenge)
        }
    }

    private fun baseBand(score: Double?): Int = when {
        score == null -> 2
        score < 40 -> 1
        score < 55 -> 2
        score < 70 -> 3
        score < 85 -> 4
        else -> 5
    }

    private fun Double?.asJson(): JsonElement = this?.let(::JsonPrimitive) ?: JsonNull
}
