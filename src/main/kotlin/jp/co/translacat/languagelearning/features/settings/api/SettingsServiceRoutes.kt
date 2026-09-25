package jp.co.translacat.languagelearning.features.settings.api

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.settings.api.dto.*
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.error.LearningBusinessException
import jp.co.translacat.languagelearning.shared.security.SETTINGS_SERVICE_AUTH

/** BE의 작업자/잔존 학습 기능 전용이다. 외부 사용자 토큰으로 다른 사용자 설정을 읽을 수 없다. */
internal fun Route.settingsServiceRoutes(operations: SettingsServiceOperations) {
    authenticate(SETTINGS_SERVICE_AUTH) {
        get("/internal/v1/service/language-learning/settings/users/{userId}") {
            val value = operations.userSnapshot(call.settingsUserId())
            call.respond(
                UserSettingsSnapshotDto(
                    value.userId, value.learningDate.toString(), value.result.settings.updatedAt.toString(),
                    value.result.toResponse(),
                ),
            )
        }
        get("/internal/v1/service/language-learning/settings/users/{userId}/learning-date") {
            call.respond(LearningDateResponseDto(operations.learningDate(call.settingsUserId()).toString()))
        }
        get("/internal/v1/service/language-learning/settings/admin") {
            call.respond(operations.adminPolicy().toResponse())
        }
        get("/internal/v1/service/language-learning/settings/listening-policy") {
            val policy = operations.listeningPolicy()
            call.respond(
                ListeningPolicyResponseDto(
                    enabled = policy.enabled,
                    defaultItemCount = policy.defaultItemCount,
                    minItemCount = policy.minItemCount,
                    maxItemCount = policy.maxItemCount,
                    hardItemLimit = policy.hardItemLimit,
                    referenceAudioMaxSeconds = policy.referenceAudioMaxSeconds,
                    repeatAudioMaxSeconds = policy.repeatAudioMaxSeconds,
                    maxAudioFileBytes = policy.maxAudioFileBytes,
                    maxRerecordCount = policy.maxRerecordCount,
                    resumeHours = policy.resumeHours,
                    referenceAudioRetentionDays = policy.referenceAudioRetentionDays,
                    userAudioRetentionDays = policy.userAudioRetentionDays,
                    reportedAudioRetentionDays = policy.reportedAudioRetentionDays,
                    automaticRetryLimit = policy.automaticRetryLimit,
                    manualRetryLimit = policy.manualRetryLimit,
                    practiceAttemptLimit = policy.practiceAttemptLimit,
                    profilePolicyVersion = policy.profilePolicyVersion,
                    modelConfigVersion = policy.modelConfigVersion,
                    referenceTtsRegenerationEnabled = policy.referenceTtsRegenerationEnabled,
                ),
            )
        }
        get("/internal/v1/service/language-learning/settings/language-pairs") {
            call.respond(
                ConfiguredLanguagePairsDto(
                    operations.configuredLanguagePairs().map {
                        ConfiguredLanguagePairDto(it.originLanguage, it.learningLanguage)
                    },
                ),
            )
        }
    }
}

private fun ApplicationCall.settingsUserId(): Long {
    val text = parameters["userId"]
    if (text == null || !Regex("[1-9][0-9]{0,18}").matches(text)) invalidUserId()
    return text.toLongOrNull()?.takeIf { it > 0 } ?: invalidUserId()
}

private fun invalidUserId(): Nothing = throw LearningBusinessException("INVALID_REQUEST", "userId는 양수 Long이어야 합니다.")
