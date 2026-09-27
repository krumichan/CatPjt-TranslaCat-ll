package jp.co.translacat.languagelearning.features.settings.api

import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import jp.co.translacat.languagelearning.features.settings.api.dto.ListeningPolicyResponseDto
import jp.co.translacat.languagelearning.features.settings.application.SettingsServiceOperations
import jp.co.translacat.languagelearning.shared.security.SETTINGS_SERVICE_AUTH

/** 현재 BE Listening 정책 조회 전용이다. 사용자 토큰으로 서비스 정책을 읽을 수 없다. */
internal fun Route.settingsServiceRoutes(operations: SettingsServiceOperations) {
    authenticate(SETTINGS_SERVICE_AUTH) {
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
    }
}
