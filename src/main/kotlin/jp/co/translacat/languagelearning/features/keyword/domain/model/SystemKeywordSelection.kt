package jp.co.translacat.languagelearning.features.keyword.domain.model

import java.time.LocalDate

internal data class SystemKeywordSelection(
    val id: Long = 0,
    val userId: Long,
    val systemKeywordId: Long,
    val active: Boolean,
    val availableFrom: LocalDate,
    val pendingActive: Boolean?,
    val pendingEffectiveDate: LocalDate?,
) {
    val desiredActive: Boolean get() = pendingActive ?: active

    fun promote(date: LocalDate, immediately: Boolean = false): SystemKeywordSelection {
        val effective = pendingEffectiveDate ?: return this
        if (!immediately && effective.isAfter(date)) return this
        return copy(active = pendingActive == true, pendingActive = null, pendingEffectiveDate = null)
    }
}
