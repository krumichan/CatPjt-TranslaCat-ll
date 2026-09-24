package jp.co.translacat.languagelearning.features.keyword.domain.repository

import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeyword
import java.time.LocalDateTime

internal interface SystemKeywordRepository {
    fun findAll(): List<SystemKeyword>
    fun save(keyword: SystemKeyword, actorId: Long, now: LocalDateTime): SystemKeyword
}
