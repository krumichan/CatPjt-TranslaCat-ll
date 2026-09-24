package jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.settings.domain.exception.SettingsPolicyNotInitializedException
import jp.co.translacat.languagelearning.features.settings.domain.model.GoalPolicy
import jp.co.translacat.languagelearning.features.settings.domain.model.InitialSettingsPolicy
import jp.co.translacat.languagelearning.features.settings.domain.repository.SettingsPolicyRepository
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table.AdminSettingsTable
import jp.co.translacat.languagelearning.features.settings.infrastructure.persistence.table.ListeningPoliciesTable
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

internal class ExposedSettingsPolicyRepository(private val requireTransaction: () -> Unit) : SettingsPolicyRepository {
    override fun loadInitialPolicy(): InitialSettingsPolicy {
        requireTransaction()
        val admin = AdminSettingsTable.selectAll()
            .where { AdminSettingsTable.id eq "DEFAULT" }
            .singleOrNull()
            ?: throw SettingsPolicyNotInitializedException("admin DEFAULT")
        val listening = ListeningPoliciesTable.selectAll()
            .where { ListeningPoliciesTable.id eq "DEFAULT" }
            .singleOrNull()
            ?: throw SettingsPolicyNotInitializedException("listening DEFAULT")
        return InitialSettingsPolicy(
            writing = GoalPolicy(
                admin[AdminSettingsTable.defaultDailySentenceCount],
                admin[AdminSettingsTable.minDailySentenceCount],
                admin[AdminSettingsTable.maxDailySentenceCount],
            ),
            speaking = GoalPolicy(
                admin[AdminSettingsTable.defaultDailySpeakingGoalMinutes],
                admin[AdminSettingsTable.minDailySpeakingGoalMinutes],
                admin[AdminSettingsTable.maxDailySpeakingGoalMinutes],
            ),
            listening = GoalPolicy(
                listening[ListeningPoliciesTable.defaultItemCount],
                listening[ListeningPoliciesTable.minItemCount],
                listening[ListeningPoliciesTable.maxItemCount],
            ),
        )
    }
}
