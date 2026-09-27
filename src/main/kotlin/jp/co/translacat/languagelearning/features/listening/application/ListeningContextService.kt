package jp.co.translacat.languagelearning.features.listening.application

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.keyword.domain.policy.KeywordSelectionPolicy
import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningSetState
import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningTaskSelectionPolicy
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import kotlinx.serialization.json.*

/** 기존 DailySet의 설정·키워드·profile snapshot과 최초 생성 작업을 LL에서 저장한다. */
internal class ListeningContextService(
    private val settings: SettingsServiceOperations,
    private val keywords: KeywordOperations,
    private val work: ListeningUnitOfWork,
    private val learningFacts: KeywordLearningFacts,
) {
    suspend fun getOrCreate(
        userId: Long, learningMode: String = "DICTATION",
        difficulty: String = "MY_LEVEL", itemCount: Int? = null,
    ): ListeningSetState {
        // 사용자의 현지 학습일과 언어를 적용하고 이미 만든 세트는 정책 변경 전 snapshot 그대로 돌려준다.
        val context = settings.userSnapshot(userId)
        val user = context.result.settings
        UserSettingsPolicy.requireConfigured(user)
        ListeningTaskSelectionPolicy.tasksForMode(learningMode)
        require(difficulty in setOf("EASY", "MY_LEVEL", "CHALLENGE"))
        val language = checkNotNull(user.learningLanguage)
        val date = context.learningDate
        work.read {
            sets(
                userId, date,
            ).firstOrNull { it.learningLanguage == language && it.learningMode == learningMode }
        }
            ?.let { return it }
        val policy = settings.listeningPolicy()
        if (!policy.enabled) throw LearningBusinessException(
            "LISTENING_SETTING_REQUIRED", "Listening 학습 기능이 비활성화되어 있습니다.",
        )
        val count = itemCount ?: user.dailyListeningGoalCount
        if (count !in policy.minItemCount..policy.maxItemCount || count > policy.hardItemLimit) {
            throw LearningBusinessException("LISTENING_INVALID_STATE", "Listening Item Count가 허용 범위를 벗어났습니다.")
        }
        val admin = settings.adminPolicy()
        // 평가 Activity가 없어도 공개 Speaking 세션이 있으면 내일 예약을 오늘로 승격하지 않는다.
        val candidates = if (admin.dailyKeywordMaxCount > 0)
            keywords.candidates(userId, learningFacts.hasStartedLearning(userId), date) else emptyList()

        // 사용자 잠금 아래 중복 생성을 재확인하고 선택 이력·세트·최초 outbox를 함께 저장한다.
        return work.write(userId) {
            sets(userId, date).firstOrNull { it.learningLanguage == language && it.learningMode == learningMode }
                ?.let { return@write it }
            val selected = KeywordSelectionPolicy.select(userId, date, candidates, admin.dailyKeywordMaxCount) {
                growth.mastery(userId, it)
            }
            if (selected.isNotEmpty()) GrowthProjector(growth).apply(
                userId,
                GrowthChange.KeywordsSelected(date, selected.map { it.canonicalKey }), nowUtc,
            )
            val profile = growth.profile(userId)
            val base = profile?.baseLevelScore
            val baseBand = when {
                base == null -> 2; base < 40 -> 1; base < 55 -> 2; base < 70 -> 3; base < 85 -> 4; else -> 5
            }
            val targetBand = (baseBand + when (difficulty) {
                "EASY" -> -1; "CHALLENGE" -> 1; else -> 0
            }).coerceIn(1, 5)
            val id = allocateId()
            val request = buildJsonObject {
                put(
                    "userContext",
                    buildJsonObject {
                        put("originLanguage", checkNotNull(user.originLanguage))
                        put("learningLanguage", language)
                        put("level", difficulty)
                        put(
                            "profileFocus",
                            JsonArray(
                                growth.signals(userId, "RECOMMENDED_FOCUS", 10).map { it.key }
                                    .filter(String::isNotBlank).distinct().take(30).map(::JsonPrimitive),
                            ),
                        )
                    },
                )
                put(
                    "setContext",
                    buildJsonObject {
                        put("learningDate", date.toString())
                        put("learningMode", learningMode)
                        put("topic", buildJsonObject { put("id", "daily"); put("title", "Daily Listening") })
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
                        put("itemCount", 1)
                        put("difficulty", difficulty)
                    },
                )
                put(
                    "languageComplexity",
                    buildJsonObject {
                        put("baseLevelScore", base?.let(::JsonPrimitive) ?: JsonNull)
                        put("baseComplexityBand", baseBand)
                        put("targetComplexityBand", targetBand)
                        put("policyVersion", "language-complexity")
                    },
                )
                put("policyVersion", policy.profilePolicyVersion)
                put("modelConfigVersion", policy.modelConfigVersion)
                put("contentDiversityPolicyVersion", "language-learning-diversity")
                put(
                    "referenceVoice",
                    buildJsonObject {
                        put("locale", language)
                        put("voiceKey", "marin")
                        put("version", "openai-speech-v1")
                        put("accent", "STANDARD")
                    },
                )
            }
            ListeningSetState(
                id, userId, date.toString(), checkNotNull(user.originLanguage), language,
                learningMode, difficulty, count, request,
            ).also { set ->
                insertSet(set)
                enqueue(
                    userId, id, "GENERATE", "listening:set:$id:generate:0",
                    buildJsonObject {
                        put("logicalItemIndex", 1)
                        put("manualRetryAttempt", 0)
                        put("replacementSequence", 0)
                    },
                )
            }
        }
    }
}
