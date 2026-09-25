package jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeywordSelection
import jp.co.translacat.languagelearning.features.keyword.domain.repository.SystemKeywordSelectionRepository
import jp.co.translacat.languagelearning.features.keyword.infrastructure.persistence.table.SystemKeywordSelectionsTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDateTime

internal class ExposedSystemKeywordSelectionRepository(private val requireTransaction: () -> Unit) :
    SystemKeywordSelectionRepository {
    override fun findForUser(userId: Long): List<SystemKeywordSelection> {
        requireTransaction()
        return SystemKeywordSelectionsTable.selectAll()
            .where { SystemKeywordSelectionsTable.userId eq userId }
            .orderBy(SystemKeywordSelectionsTable.id to SortOrder.ASC)
            .forUpdate()
            .map(::toModel)
    }

    override fun save(selection: SystemKeywordSelection, actorId: Long, now: LocalDateTime): SystemKeywordSelection {
        requireTransaction()
        val value = selection
        val id = if (value.id == 0L) {
            SystemKeywordSelectionsTable.insert {
                it[SystemKeywordSelectionsTable.userId] = value.userId
                it[SystemKeywordSelectionsTable.systemKeywordId] = value.systemKeywordId
                it[SystemKeywordSelectionsTable.active] = value.active
                it[SystemKeywordSelectionsTable.availableFrom] = value.availableFrom
                it[SystemKeywordSelectionsTable.pendingActive] = value.pendingActive
                it[SystemKeywordSelectionsTable.pendingEffectiveDate] = value.pendingEffectiveDate
                it[SystemKeywordSelectionsTable.createdBy] = actorId.toString()
                it[SystemKeywordSelectionsTable.createdAt] = now
                it[SystemKeywordSelectionsTable.updatedBy] = actorId.toString()
                it[SystemKeywordSelectionsTable.updatedAt] = now
            }[SystemKeywordSelectionsTable.id]
        } else {
            val count =
                SystemKeywordSelectionsTable.update(
                    { (SystemKeywordSelectionsTable.id eq value.id) and (SystemKeywordSelectionsTable.userId eq value.userId) },
                ) {
                    it[SystemKeywordSelectionsTable.systemKeywordId] = value.systemKeywordId
                    it[SystemKeywordSelectionsTable.active] = value.active
                    it[SystemKeywordSelectionsTable.availableFrom] = value.availableFrom
                    it[SystemKeywordSelectionsTable.pendingActive] = value.pendingActive
                    it[SystemKeywordSelectionsTable.pendingEffectiveDate] = value.pendingEffectiveDate
                    it[SystemKeywordSelectionsTable.updatedBy] = actorId.toString()
                    it[SystemKeywordSelectionsTable.updatedAt] = now
                }
            check(count in 0..1) { "키워드 갱신 행 수가 올바르지 않습니다." }
            value.id
        }
        val saved = SystemKeywordSelectionsTable.selectAll()
            .where { SystemKeywordSelectionsTable.id eq id }
            .forUpdate()
            .single()
            .let(::toModel)
        check(saved == value.copy(id = id)) { "키워드 저장 결과가 요청과 다릅니다." }
        return saved
    }

    private fun toModel(row: ResultRow) = SystemKeywordSelection(
        id = row[SystemKeywordSelectionsTable.id],
        userId = row[SystemKeywordSelectionsTable.userId],
        systemKeywordId = row[SystemKeywordSelectionsTable.systemKeywordId],
        active = row[SystemKeywordSelectionsTable.active],
        availableFrom = row[SystemKeywordSelectionsTable.availableFrom],
        pendingActive = row[SystemKeywordSelectionsTable.pendingActive],
        pendingEffectiveDate = row[SystemKeywordSelectionsTable.pendingEffectiveDate],
    )
}
