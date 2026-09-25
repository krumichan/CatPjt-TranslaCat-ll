package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence

import jp.co.translacat.languagelearning.features.listening.domain.model.ListeningPolicy
import jp.co.translacat.languagelearning.features.settings.application.SettingsReadQueries
import jp.co.translacat.languagelearning.features.settings.domain.exception.SettingsPolicyNotInitializedException
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table.ListeningPoliciesTable
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table.UserSettingsTable
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll

internal class ExposedSettingsReadQueries(private val transactions: JdbcTransactionRunner) : SettingsReadQueries {
    override suspend fun findTimezone(userId: Long): String? = transactions.read {
        UserSettingsTable.select(UserSettingsTable.timezone)
            .where { UserSettingsTable.userId eq userId }
            .singleOrNull()?.get(UserSettingsTable.timezone)
    }

    override suspend fun configuredLanguagePairs(): List<ConfiguredLanguagePair> = transactions.read {
        // 기존 보충 배치처럼 활성 언어만 읽는다. 이 조회가 pending을 승격하거나 사용자 행을 만들지 않는다.
        UserSettingsTable.select(UserSettingsTable.originLanguage, UserSettingsTable.learningLanguage)
            .withDistinct()
            .where { UserSettingsTable.originLanguage.isNotNull() and UserSettingsTable.learningLanguage.isNotNull() }
            .mapNotNull { row ->
                val origin = row[UserSettingsTable.originLanguage]
                val learning = row[UserSettingsTable.learningLanguage]
                if (origin.isNullOrBlank() || learning.isNullOrBlank()) null else ConfiguredLanguagePair(
                    origin, learning,
                )
            }
            .distinct()
            .sortedWith(compareBy({ it.originLanguage }, { it.learningLanguage }))
    }

    override suspend fun listeningPolicy(): ListeningPolicy = transactions.read {
        val row = ListeningPoliciesTable.selectAll()
            .where { ListeningPoliciesTable.id eq "DEFAULT" }
            .singleOrNull() ?: throw SettingsPolicyNotInitializedException("LISTENING")
        ListeningPolicy(
            enabled = row[ListeningPoliciesTable.enabled],
            defaultItemCount = row[ListeningPoliciesTable.defaultItemCount],
            minItemCount = row[ListeningPoliciesTable.minItemCount],
            maxItemCount = row[ListeningPoliciesTable.maxItemCount],
            hardItemLimit = row[ListeningPoliciesTable.hardItemLimit],
            referenceAudioMaxSeconds = row[ListeningPoliciesTable.referenceAudioMaxSeconds],
            repeatAudioMaxSeconds = row[ListeningPoliciesTable.repeatAudioMaxSeconds],
            maxAudioFileBytes = row[ListeningPoliciesTable.maxAudioFileBytes],
            maxRerecordCount = row[ListeningPoliciesTable.maxRerecordCount],
            resumeHours = row[ListeningPoliciesTable.resumeHours],
            referenceAudioRetentionDays = row[ListeningPoliciesTable.referenceAudioRetentionDays],
            userAudioRetentionDays = row[ListeningPoliciesTable.userAudioRetentionDays],
            reportedAudioRetentionDays = row[ListeningPoliciesTable.reportedAudioRetentionDays],
            automaticRetryLimit = row[ListeningPoliciesTable.automaticRetryLimit],
            manualRetryLimit = row[ListeningPoliciesTable.manualRetryLimit],
            practiceAttemptLimit = row[ListeningPoliciesTable.practiceAttemptLimit],
            profilePolicyVersion = row[ListeningPoliciesTable.profilePolicyVersion],
            modelConfigVersion = row[ListeningPoliciesTable.modelConfigVersion],
            referenceTtsRegenerationEnabled = row[ListeningPoliciesTable.referenceTtsRegenerationEnabled],
        )
    }
}
