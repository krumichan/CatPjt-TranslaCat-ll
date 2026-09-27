package jp.co.translacat.languagelearning.features.practice.application

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.keyword.domain.policy.KeywordSelectionPolicy
import jp.co.translacat.languagelearning.features.practice.domain.*
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy
import kotlinx.serialization.json.Json
import java.util.*

internal class PracticeContextService(
    private val settings: SettingsServiceOperations,
    private val keywords: KeywordOperations,
    private val work: PracticeUnitOfWork,
    private val learningFacts: KeywordLearningFacts,
) {
    suspend fun today(userId: Long, domain: PracticeDomain, mode: String): PracticeSet {
        // 퇴역 제품은 설정 조회나 모델 호출 전에 기존 Core 오류 계약으로 종료한다.
        if (domain == PracticeDomain.VOCABULARY) throw PracticeFailure(PracticePolicy.VOCABULARY_RETIRED, 400)
        if (mode !in PracticePolicy.readingModes) throw PracticeFailure("LANGUAGE_LEARNING_SETTING_INVALID", 400)
        val snapshot = settings.userSnapshot(userId)
        val user = snapshot.result.settings
        UserSettingsPolicy.requireConfigured(user)
        val date = snapshot.learningDate
        val existing = work.write(userId) {
            GrowthProjector(growth).apply(userId, GrowthChange.LearningPrepared(date), nowUtc)
            records.today(userId, date, domain).firstOrNull { it.mode == mode }
        }
        if (existing != null) return existing
        // Writing 세트·공개 Speaking 세션의 원본 시작 기준으로 키워드 예약을 유지한다.
        val candidates = keywords.candidates(userId, learningFacts.hasStartedLearning(userId), date)

        // 사용자 잠금 아래 재확인한 뒤 기존 top-8 가중 순서와 성장 신호를 snapshot에 고정한다.
        return work.write(userId) {
            records.today(userId, date, domain).firstOrNull { it.mode == mode }?.let { return@write it }
            val profile = growth.profile(userId) ?: throw PracticeFailure("LANGUAGE_LEARNING_LEVEL_TEST_REQUIRED", 400)
            val band = PracticePolicy.complexityBand(profile.baseLevelScore, records.recentScores(userId, domain, mode))
            PracticePolicy.requireNewLearning(domain, mode, band)
            val selected = candidates.sortedByDescending { candidate ->
                val canonical =
                    candidate.canonicalKey?.takeUnless(String::isBlank) ?: candidate.text.lowercase(Locale.ROOT)
                KeywordSelectionPolicy.rawWeight(candidate.availableFrom, growth.mastery(userId, canonical), date)
            }.take(8).map { it.text }.filter(String::isNotBlank)
            val weak = (growth.signals(userId, "WEAKNESS", 8) + growth.signals(userId, "RECOMMENDED_FOCUS", 8))
                .map { it.key }.distinct().take(12)
            val mistakes = records.recentMistakes(userId, domain).map {
                if (it.targetExpression == null) it.skillTag else "${it.skillTag}:${it.targetExpression}"
            }.distinct().take(12)
            val request = ReadingRequest(
                "practice-$userId-$date-$domain-$mode-${UUID.randomUUID()}", mode,
                checkNotNull(user.originLanguage), checkNotNull(user.learningLanguage), band, selected, weak, mistakes,
                date.toString(),
            )
            records.save(
                PracticeSet(0, userId, date, domain, mode, band, 5, Json.encodeToString(request), startedAt = nowUtc),
            )
        }
    }

    suspend fun availability(userId: Long): List<Pair<String, Boolean>> {
        val snapshot = settings.userSnapshot(userId)
        UserSettingsPolicy.requireConfigured(snapshot.result.settings)
        return work.read {
            PracticePolicy.readingModes.map { mode ->
                val band = PracticePolicy.complexityBand(
                    growth.profile(userId)?.baseLevelScore,
                    records.recentScores(userId, PracticeDomain.READING, mode),
                )
                mode to (mode != "STRUCTURE" || PracticePolicy.slots(mode, band).none { it.complexityBand == 5 })
            }
        }
    }
}
