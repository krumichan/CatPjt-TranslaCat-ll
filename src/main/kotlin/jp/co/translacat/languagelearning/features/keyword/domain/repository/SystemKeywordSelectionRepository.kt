package jp.co.translacat.languagelearning.features.keyword.domain.repository

import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeywordSelection
import java.time.LocalDateTime

internal interface SystemKeywordSelectionRepository {
    fun findForUser(userId: Long): List<SystemKeywordSelection>
    fun save(selection: SystemKeywordSelection, actorId: Long, now: LocalDateTime): SystemKeywordSelection
}
