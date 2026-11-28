package jp.co.translacat.languagelearning.features.practice.application

import jp.co.translacat.languagelearning.features.autopractice.application.AutoPracticeAccess
import jp.co.translacat.languagelearning.features.autopractice.domain.AutoPracticeArea
import jp.co.translacat.languagelearning.features.autopractice.domain.AutoPracticeJob
import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.keyword.application.KeywordLearningFacts
import jp.co.translacat.languagelearning.features.keyword.application.KeywordOperations
import jp.co.translacat.languagelearning.features.keyword.domain.policy.KeywordSelectionPolicy
import jp.co.translacat.languagelearning.features.practice.domain.*
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.features.settings.domain.policy.UserSettingsPolicy
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.util.*

internal class PracticeContextService(
    private val settings: SettingsServiceOperations,
    private val keywords: KeywordOperations,
    private val work: PracticeUnitOfWork,
    private val learningFacts: KeywordLearningFacts,
    private val autoPractice: AutoPracticeAccess? = null,
) {
    /** 기존 Reading 세트 생성과 배치의 지정 학습일·난이도 구성을 같은 저장 경로에 전달한다. */
    suspend fun today(userId: Long, domain: PracticeDomain, mode: String, scheduled: AutoPracticeJob? = null): PracticeSet {
        // 퇴역 제품은 설정 조회나 모델 호출 전에 기존 Core 오류 계약으로 종료한다.
        if (domain == PracticeDomain.VOCABULARY) throw PracticeFailure(PracticePolicy.VOCABULARY_RETIRED, 400)
        if (mode !in PracticePolicy.readingModes) throw PracticeFailure("LANGUAGE_LEARNING_SETTING_INVALID", 400)
        val snapshot = settings.userSnapshot(userId)
        val user = snapshot.result.settings
        UserSettingsPolicy.requireConfigured(user)
        val origin = checkNotNull(user.originLanguage)
        val learning = checkNotNull(user.learningLanguage)
        if (scheduled != null && !ConfiguredLanguagePair(scheduled.originLanguage, scheduled.learningLanguage)
                .matches(origin, learning)
        ) throw PracticeFailure("LANGUAGE_LEARNING_SETTING_INVALID", 400)
        val date = scheduled?.let { LocalDate.parse(it.learningDate) } ?: snapshot.learningDate
        if (scheduled == null) {
            val assignment = autoPractice?.assigned(userId, origin, learning, AutoPracticeArea.valueOf("READING_$mode"), date)
            if (assignment != null) return work.read {
                assignment.resourceIds.firstNotNullOfOrNull { records.find(userId, it) }
            } ?: throw PracticeFailure("AUTO_PRACTICE_PREPARING", 409)
        }
        val difficulties = scheduled?.quantity?.let {
            List(it.easy) { PracticeDifficulty.EASIER } + List(it.standard) { PracticeDifficulty.CURRENT } +
                List(it.hard) { PracticeDifficulty.CHALLENGE }
        }
        val existing = work.write(userId) {
            // 문맥을 읽은 뒤 자정 승격이 일어나도 같은 snapshot 언어의 일일 세트와 기준점만 사용한다.
            if (scheduled == null) {
                GrowthProjector(growth.scoped(origin, learning)).apply(userId, GrowthChange.LearningPrepared(date), nowUtc)
            }
            records.scoped(origin, learning).today(userId, date, domain).firstOrNull { it.mode == mode }
        }
        if (existing != null) return existing
        // Writing 세트·공개 Speaking 세션의 원본 시작 기준으로 키워드 예약을 유지한다.
        val candidates = keywords.candidates(userId, learningFacts.hasStartedLearning(userId), date)

        // 사용자 잠금 아래 재확인한 뒤 기존 top-8 가중 순서와 성장 신호를 snapshot에 고정한다.
        return work.write(userId) {
            val sourceRecords = records.scoped(origin, learning)
            val sourceGrowth = growth.scoped(origin, learning)
            sourceRecords.today(userId, date, domain).firstOrNull { it.mode == mode }?.let { return@write it }
            val profile = sourceGrowth.profile(userId) ?: throw PracticeFailure("LANGUAGE_LEARNING_LEVEL_TEST_REQUIRED", 400)
            val band = PracticePolicy.complexityBand(profile.baseLevelScore, sourceRecords.recentScores(userId, domain, mode))
            PracticePolicy.requireNewLearning(domain, mode, band, difficulties)
            val selected = candidates.sortedByDescending { candidate ->
                val canonical =
                    candidate.canonicalKey?.takeUnless(String::isBlank) ?: candidate.text.lowercase(Locale.ROOT)
                KeywordSelectionPolicy.rawWeight(candidate.availableFrom, sourceGrowth.mastery(userId, canonical), date)
            }.take(8).map { it.text }.filter(String::isNotBlank)
            val weak = (sourceGrowth.signals(userId, "WEAKNESS", 8) + sourceGrowth.signals(userId, "RECOMMENDED_FOCUS", 8))
                .map { it.key }.distinct().take(12)
            val mistakes = sourceRecords.recentMistakes(userId, domain).map {
                if (it.targetExpression == null) it.skillTag else "${it.skillTag}:${it.targetExpression}"
            }.distinct().take(12)
            val request = ReadingRequest(
                "practice-$userId-$date-$domain-$mode-${UUID.randomUUID()}", mode,
                origin, learning, band, selected, weak, mistakes,
                date.toString(), difficulties, scheduled?.id,
            )
            sourceRecords.save(
                PracticeSet(0, userId, date, domain, mode, band, difficulties?.size ?: 5,
                    Json.encodeToString(request), startedAt = nowUtc),
            )
        }
    }

    /** 세트 소유권과 생성 상태를 읽으며 AI나 생성 상태에 영향을 주지 않는다. */
    suspend fun preparedStatus(userId: Long, setId: Long): String = work.read {
        records.find(userId, setId)?.let { if (it.status == PracticeStatus.COMPLETED) "COMPLETED" else it.generationStatus.name }
            ?: "UNAVAILABLE"
    }

    suspend fun availability(userId: Long): List<Pair<String, Boolean>> {
        val snapshot = settings.userSnapshot(userId)
        val user = snapshot.result.settings
        UserSettingsPolicy.requireConfigured(user)
        val origin = checkNotNull(user.originLanguage)
        val learning = checkNotNull(user.learningLanguage)
        return work.read {
            val sourceRecords = records.scoped(origin, learning)
            val sourceGrowth = growth.scoped(origin, learning)
            PracticePolicy.readingModes.map { mode ->
                val band = PracticePolicy.complexityBand(
                    sourceGrowth.profile(userId)?.baseLevelScore,
                    sourceRecords.recentScores(userId, PracticeDomain.READING, mode),
                )
                mode to (mode != "STRUCTURE" || PracticePolicy.slots(mode, band).none { it.complexityBand == 5 })
            }
        }
    }
}
