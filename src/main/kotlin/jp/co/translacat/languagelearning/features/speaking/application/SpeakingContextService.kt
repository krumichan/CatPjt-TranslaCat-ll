package jp.co.translacat.languagelearning.features.speaking.application

import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.keyword.domain.policy.KeywordSelectionPolicy
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy
import jp.co.translacat.languagelearning.features.speaking.domain.*
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import kotlinx.serialization.json.*

internal class SpeakingContextService(
    private val settings: SettingsServiceOperations,
    private val keywords: KeywordOperations,
    private val work: SpeakingUnitOfWork,
    private val learningFacts: KeywordLearningFacts,
    private val speakingRecommendedFocus: suspend (Long) -> List<String>,
) {
    suspend fun create(userId: Long, request: SpeakingCreateRequest): SpeakingSessionRecord {
        // 기존 멱등 요청은 현재 설정·활성 세션·한도 검사를 다시 하지 않고 반환한다.
        if (!request.idempotencyKey.isNullOrBlank()) {
            work.read { records.sessionByIdempotency(userId, request.idempotencyKey) }?.let { return it }
        }

        // 현재 설정과 추천 문맥을 준비한다. 실제 선택 결과는 아래 사용자 잠금 안에서 고정한다.
        val current = settings.userSnapshot(userId)
        val user = current.result.settings
        val admin = settings.adminPolicy()
        UserSettingsPolicy.requireConfigured(user)
        SpeakingSessionPolicy.validateCreate(request, admin)
        val date = current.learningDate
        val candidates = if ((request.keywordBasedTopic || request.topicId != null) && admin.dailyKeywordMaxCount > 0)
            keywords.candidates(userId, learningFacts.hasStartedLearning(userId), date) else emptyList()
        val focus = speakingRecommendedFocus(userId)

        return work.write(userId) {
            // 사용자 잠금 아래 활성 세션과 일일 한도를 재확인한 후 선택 기록과 세션을 함께 저장한다.
            records.sessionByIdempotency(userId, checkNotNull(request.idempotencyKey))?.let { return@write it }
            SpeakingSessionPolicy.requireRemainingLimit(
                records.sessions(userId, date, date).map { it.totalDurationSeconds }, admin,
            )
            records.active(userId)?.let {
                val active = it.expireIfNeeded(nowUtc)
                if (active != it) records.saveSession(active)
                if (active.active || active.openingLeaseAlive(nowUtc)) throw SpeakingFailure("SESSION_NOT_ACTIVE")
            }

            // 선택한 토픽의 사용 가능 언어와 시작 방식을 확인한 뒤 기존 가중치로 키워드를 선택한다.
            val topic = request.topicId?.let {
                records.topic(LearningPublicId.decode(it.toString()))?.takeIf { value -> value.active }
                    ?: throw SpeakingFailure("SPEAKING_TOPIC_NOT_FOUND")
            }
            if (topic?.learningLanguage != null && !topic.learningLanguage.equals(user.learningLanguage, true)) {
                throw SpeakingFailure("SPEAKING_TOPIC_NOT_FOUND")
            }
            val mode = checkNotNull(request.practiceMode)
            val resolved = SpeakingSessionPolicy.resolveStart(
                checkNotNull(request.conversationStartMode), topic?.recommendedStartMode,
            )
            SpeakingSessionPolicy.requireResolvedStart(mode, resolved)
            val selected = KeywordSelectionPolicy.select(userId, date, candidates, admin.dailyKeywordMaxCount) {
                growth.mastery(userId, it)
            }
            if (request.keywordBasedTopic && selected.isEmpty()) throw SpeakingFailure(
                "LANGUAGE_LEARNING_SETTING_INVALID",
            )
            val selection = selected.map { value ->
                buildJsonObject {
                    put("key", value.candidate.key)
                    put("text", value.candidate.text)
                    put("source", value.candidate.source.name)
                    put("type", value.candidate.type.name)
                    put("canonicalKey", value.canonicalKey)
                    put("selectionWeight", value.weight)
                }
            }

            // 이후 설정 변경의 영향을 받지 않도록 정책과 문맥을 세션 snapshot에 저장한다.
            val free = mode == SpeakingPracticeMode.FREE
            val title = if (request.keywordBasedTopic) selected.map { it.candidate.text }.filter(String::isNotBlank)
                .take(5).joinToString(" · ").ifBlank { "Keyword-based Speaking" }.take(500)
            else topic?.title ?: checkNotNull(request.customTopic).trim()
            val requestedVoice = request.voiceId ?: user.speakingVoiceId
            val snapshot = SpeakingSessionSnapshot(
                topic?.id, title,
                if (request.keywordBasedTopic) "KEYWORDS" else topic?.category ?: "FREE_TALK", topic?.version,
                if (request.keywordBasedTopic) null else request.customTopic?.trim(),
                if (free) request.goal?.trim() else null, if (free) request.persona?.trim() else null,
                selection, checkNotNull(user.originLanguage), checkNotNull(user.learningLanguage), mode,
                request.conversationStartMode, resolved, checkNotNull(request.correctionMode), request.targetMinutes,
                SpeakingSessionPolicy.maxTurns(mode, admin.maxTurnsPerSession),
                if (requestedVoice.isNullOrBlank() || requestedVoice in listOf(
                        "Kore", "Aoede", "Puck",
                    )
                ) "marin" else requestedVoice,
                request.playbackSpeed ?: user.speakingPlaybackSpeed, SpeakingSessionPolicySnapshot.from(admin),
                profileSummary(userId, focus),
                if (free) SpeakingResultKind.SESSION_COACHING else SpeakingResultKind.SCORED_EVALUATION,
                if (free) "free-session-coaching-v1" else "speaking-evaluation-policy-v2",
            )

            // opening 준비 전에는 PENDING으로 보관하고 성공 공개는 opening 실행기가 담당한다.
            records.saveSession(
                SpeakingSessionRecord(
                    userId = userId, createIdempotencyKey = request.idempotencyKey,
                    learningDate = date, snapshot = snapshot, startedAt = nowUtc,
                    opening = buildJsonObject {
                        put("_executionState", "PENDING")
                        put("_leaseUntil", nowUtc.plusSeconds(180).toString())
                    },
                ),
            )
        }
    }

    private fun SpeakingTransaction.profileSummary(userId: Long, speakingFocus: List<String>): JsonObject? {
        val profile = growth.profile(userId) ?: return null
        fun keys(type: String) = growth.signals(userId, type, 10).map { it.key }
        fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
        fun number(value: Double?) = value?.let(::JsonPrimitive) ?: JsonNull
        val scores = listOf(
            profile.meaningScore, profile.grammarScore, profile.vocabularyScore, profile.naturalnessScore,
            profile.expressionScore,
        )
        return buildJsonObject {
            put("profileVersion", profile.profileVersion)
            put("baseLevelScore", number(profile.baseLevelScore))
            put(
                "skillScores",
                if (scores.all { it == null }) JsonNull else buildJsonObject {
                    listOf("meaning", "grammar", "vocabulary", "naturalness", "expression").zip(scores)
                        .forEach { (key, value) -> put(key, value ?: 0.0) }
                },
            )
            put("grammarWeaknesses", strings(keys("GRAMMAR_WEAKNESS")))
            put(
                "keywordMasteries",
                JsonArray(
                    growth.masteries(userId, null, 30).map { value ->
                        buildJsonObject {
                            put("canonicalKey", value.canonicalKey)
                            put("score", value.score)
                        }
                    },
                ),
            )
            put(
                "difficultyPerformance",
                buildJsonObject {
                    put("review", number(profile.reviewPerformance))
                    put("normal", number(profile.normalPerformance))
                    put("challenge", number(profile.challengePerformance))
                },
            )
            put("errorPatterns", strings(keys("ERROR_PATTERN")))
            put("trend", profile.trend)
            put("confidence", profile.confidence)
            put("strengths", strings(keys("STRENGTH")))
            put("weaknesses", strings(keys("WEAKNESS")))
            put("recommendedFocus", strings((speakingFocus + keys("RECOMMENDED_FOCUS")).distinct().take(10)))
            put("additionalSignals", Json.parseToJsonElement(profile.additionalSignalsJson))
        }
    }
}
