package jp.co.translacat.languagelearning.features.settings.domain.policy

import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningTaskSelectionPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.GoalPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.InitialSettingsPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettings
import jp.co.translacat.languagelearning.features.settings.domain.model.UserSettingsChange
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.*

/** DB/HTTP와 무관한 기존 BE의 조회·변경 규칙이다. */
internal object UserSettingsPolicy {
    const val INVALID = "LANGUAGE_LEARNING_SETTING_INVALID"
    const val NOT_CONFIGURED = "LANGUAGE_LEARNING_SETTING_NOT_CONFIGURED"
    private val defaultZone = ZoneId.of("Asia/Tokyo")

    fun today(timezone: String?, nowUtc: LocalDateTime): LocalDate {
        val zone = try {
            ZoneId.of(timezone ?: "Asia/Tokyo")
        } catch (_: Exception) {
            defaultZone
        }
        return nowUtc.toInstant(ZoneOffset.UTC).atZone(zone).toLocalDate()
    }

    fun requireConfigured(setting: UserSettings) {
        if (!setting.configured) throw LearningBusinessException(
            NOT_CONFIGURED, "Origin Language와 Learning Language 설정이 필요합니다.",
        )
    }

    fun synchronize(setting: UserSettings, policy: InitialSettingsPolicy, nowUtc: LocalDateTime): UserSettings {
        // 적용일 판정은 pendingTimezone이 아닌 승격 전의 활성 timezone으로 한다.
        val date = today(setting.timezone, nowUtc)
        val promoted = if (setting.pendingEffectiveDate?.let { !it.isAfter(date) } == true) {
            setting.copy(
                originLanguage = setting.pendingOriginLanguage ?: setting.originLanguage,
                learningLanguage = setting.pendingLearningLanguage ?: setting.learningLanguage,
                timezone = setting.pendingTimezone ?: setting.timezone,
                dailySentenceCount = setting.pendingDailySentenceCount ?: setting.dailySentenceCount,
                dailySpeakingGoalMinutes = setting.pendingDailySpeakingGoalMinutes ?: setting.dailySpeakingGoalMinutes,
                dailyListeningGoalCount = setting.pendingDailyListeningGoalCount ?: setting.dailyListeningGoalCount,
                pendingOriginLanguage = null, pendingLearningLanguage = null, pendingTimezone = null,
                pendingDailySentenceCount = null, pendingDailySpeakingGoalMinutes = null,
                pendingDailyListeningGoalCount = null, pendingEffectiveDate = null,
            )
        } else setting
        // 조회도 변경 트랜잭션이다. 승격 후 활성값과 남아 있는 예약값을 모두 보정한다.
        return promoted.copy(
            dailySentenceCount = clamp(promoted.dailySentenceCount, policy.writing),
            dailySpeakingGoalMinutes = clamp(promoted.dailySpeakingGoalMinutes, policy.speaking),
            dailyListeningGoalCount = clamp(promoted.dailyListeningGoalCount, policy.listening),
            pendingDailySentenceCount = promoted.pendingDailySentenceCount?.let { clamp(it, policy.writing) },
            pendingDailySpeakingGoalMinutes = promoted.pendingDailySpeakingGoalMinutes?.let {
                clamp(
                    it, policy.speaking
                )
            },
            pendingDailyListeningGoalCount = promoted.pendingDailyListeningGoalCount?.let {
                clamp(
                    it, policy.listening
                )
            },
        )
    }

    /** synchronize를 같은 트랜잭션에서 먼저 실행한 상태를 받는다. */
    fun change(
        setting: UserSettings, request: UserSettingsChange, policy: InitialSettingsPolicy, nowUtc: LocalDateTime
    ): UserSettings {
        val origin = cleanLanguage(request.originLanguage)
        val learning = cleanLanguage(request.learningLanguage)
        val timezone = cleanTimezone(request.timezone)
        val tasks = request.defaultListeningTaskTypes?.let(ListeningTaskSelectionPolicy::toCanonicalJson)
        val voice = cleanVoice(request.speakingVoiceId)
        val speed = cleanSpeed(request.speakingPlaybackSpeed)
        validateGoal(request.dailySentenceCount, policy.writing, "Daily Sentence Count")
        validateGoal(request.dailySpeakingGoalMinutes, policy.speaking, "Daily Speaking Goal")
        validateGoal(request.dailyListeningGoalCount, policy.listening, "Daily Listening Goal")

        // 부분 PATCH가 기존 예약값과 충돌하는 경우도 거부한다.
        val nextOrigin = origin ?: setting.pendingOriginLanguage ?: setting.originLanguage
        val nextLearning = learning ?: setting.pendingLearningLanguage ?: setting.learningLanguage
        if (nextOrigin != null && nextLearning != null && nextOrigin.equals(nextLearning, ignoreCase = true)) {
            invalid("Origin Language와 Learning Language는 달라야 합니다.")
        }
        val first =
            setting.originLanguage == null && setting.learningLanguage == null && setting.pendingEffectiveDate == null
        if (first) {
            if (nextOrigin == null || nextLearning == null) throw LearningBusinessException(
                NOT_CONFIGURED, "최초 학습 설정에는 Origin Language와 Learning Language가 모두 필요합니다.",
            )
            return setting.copy(
                originLanguage = nextOrigin, learningLanguage = nextLearning,
                timezone = timezone ?: setting.timezone,
                dailySentenceCount = request.dailySentenceCount ?: setting.dailySentenceCount,
                dailySpeakingGoalMinutes = request.dailySpeakingGoalMinutes ?: setting.dailySpeakingGoalMinutes,
                dailyListeningGoalCount = request.dailyListeningGoalCount ?: setting.dailyListeningGoalCount,
                speakingVoiceId = voice ?: setting.speakingVoiceId,
                speakingPlaybackSpeed = speed ?: setting.speakingPlaybackSpeed,
                defaultListeningTaskTypesJson = tasks ?: setting.defaultListeningTaskTypesJson,
            )
        }
        val schedules =
            origin != null || learning != null || timezone != null || request.dailySentenceCount != null || request.dailySpeakingGoalMinutes != null || request.dailyListeningGoalCount != null
        return setting.copy(
            speakingVoiceId = voice ?: setting.speakingVoiceId,
            speakingPlaybackSpeed = speed ?: setting.speakingPlaybackSpeed,
            defaultListeningTaskTypesJson = tasks ?: setting.defaultListeningTaskTypesJson,
            pendingOriginLanguage = origin ?: setting.pendingOriginLanguage,
            pendingLearningLanguage = learning ?: setting.pendingLearningLanguage,
            pendingTimezone = timezone ?: setting.pendingTimezone,
            pendingDailySentenceCount = request.dailySentenceCount ?: setting.pendingDailySentenceCount,
            pendingDailySpeakingGoalMinutes = request.dailySpeakingGoalMinutes
                ?: setting.pendingDailySpeakingGoalMinutes,
            pendingDailyListeningGoalCount = request.dailyListeningGoalCount ?: setting.pendingDailyListeningGoalCount,
            pendingEffectiveDate = if (schedules) today(
                setting.timezone, nowUtc
            ).plusDays(1) else setting.pendingEffectiveDate,
        )
    }

    private fun clamp(value: Int, policy: GoalPolicy): Int = value.coerceIn(policy.minimum, policy.maximum)
    private fun validateGoal(value: Int?, policy: GoalPolicy, name: String) {
        if (value != null && value !in policy.minimum..policy.maximum) {
            val particle = if (name == "Daily Sentence Count") "가" else "이"
            invalid("$name$particle 관리자 허용 범위를 벗어났습니다.")
        }
    }

    // Java String.trim()과 같은 범위만 제거한다. 언어 코드를 임의로 소문자화하지 않는다.
    private fun String.beTrim(): String = trim { it <= ' ' }
    private fun cleanLanguage(value: String?): String? = value?.beTrim()?.also {
        if (it.length !in 2..20) invalid("언어 코드가 유효하지 않습니다.")
    }

    private fun cleanTimezone(value: String?): String? = value?.beTrim()?.also {
        try {
            ZoneId.of(it)
        } catch (_: Exception) {
            invalid("Timezone이 유효하지 않습니다.")
        }
    }

    private fun cleanVoice(value: String?): String? = value?.beTrim()?.also {
        if (it.all { character -> Character.isWhitespace(character) } || it.length > 100) invalid("Speaking Voice가 유효하지 않습니다.")
    }

    private fun cleanSpeed(value: String?): String? = value?.beTrim()?.uppercase(Locale.ROOT)?.also {
        if (it != "NORMAL" && it != "SLOW") invalid("Speaking 재생 속도가 유효하지 않습니다.")
    }

    private fun invalid(message: String): Nothing = throw LearningBusinessException(INVALID, message)
}
