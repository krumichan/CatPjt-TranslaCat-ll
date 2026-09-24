package jp.co.translacat.languagelearning.features.keyword.domain.repository

import jp.co.translacat.languagelearning.features.keyword.domain.model.CustomKeyword
import java.time.LocalDateTime

internal interface CustomKeywordRepository {
    fun findForUser(userId: Long): List<CustomKeyword>
    fun save(keyword: CustomKeyword, actorId: Long, now: LocalDateTime): CustomKeyword
    fun referencesParent(parentId: Long): Boolean
}
