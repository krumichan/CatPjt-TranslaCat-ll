package jp.co.translacat.languagelearning.support

import jp.co.translacat.languagelearning.features.keyword.application.KeywordTransaction
import jp.co.translacat.languagelearning.features.keyword.application.KeywordUnitOfWork
import jp.co.translacat.languagelearning.features.keyword.domain.model.CustomKeyword
import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeyword
import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeywordLocale
import jp.co.translacat.languagelearning.features.keyword.domain.model.SystemKeywordSelection
import jp.co.translacat.languagelearning.features.keyword.domain.repository.CustomKeywordRepository
import jp.co.translacat.languagelearning.features.keyword.domain.repository.SystemKeywordLocaleRepository
import jp.co.translacat.languagelearning.features.keyword.domain.repository.SystemKeywordRepository
import jp.co.translacat.languagelearning.features.keyword.domain.repository.SystemKeywordSelectionRepository
import java.time.LocalDateTime

/** 정책/롤백 검증용 fake다. DB unique/lock 동작은 별도 MySQL 테스트로 검증한다. */
internal class MemoryKeywordUnitOfWork : KeywordUnitOfWork {
    val systemRows = linkedMapOf<Long, SystemKeyword>()
    val customRows = linkedMapOf<Long, CustomKeyword>()
    val selectionRows = linkedMapOf<Long, SystemKeywordSelection>()
    val localeRows = mutableListOf<SystemKeywordLocale>()
    val learnerIds = mutableSetOf<Long>()
    val inactiveUsers = mutableSetOf<Long>()
    var now = LocalDateTime.parse("2026-09-24T03:00:00")
    var failWrites = false
    var writes = 0
    private var next = 1L

    override suspend fun <T> execute(learnerId: Long?, block: KeywordTransaction.() -> T): T {
        val oldSystem = systemRows.toMap()
        val oldCustom = customRows.toMap()
        val oldSelections = selectionRows.toMap()
        val oldLearners = learnerIds.toSet()
        val oldWrites = writes
        if (learnerId != null) {
            check(learnerId !in inactiveUsers) { "비활성 사용자" }
            learnerIds += learnerId
        }
        val tx = object : KeywordTransaction {
            override val nowUtc get() = now
            override val system = object : SystemKeywordRepository {
                override fun findAll() =
                    systemRows.values.sortedWith(compareBy<SystemKeyword> { it.sortOrder }.thenBy { it.id })

                override fun save(keyword: SystemKeyword, actorId: Long, now: LocalDateTime): SystemKeyword {
                    check(!failWrites); writes++
                    return keyword.copy(id = if (keyword.id == 0L) next++ else keyword.id)
                        .also { systemRows[it.id] = it }
                }
            }
            override val custom = object : CustomKeywordRepository {
                override fun findForUser(userId: Long) =
                    customRows.values.filter { it.userId == userId }.sortedBy { it.id }

                override fun save(keyword: CustomKeyword, actorId: Long, now: LocalDateTime): CustomKeyword {
                    check(!failWrites); writes++
                    return keyword.copy(id = if (keyword.id == 0L) next++ else keyword.id)
                        .also { customRows[it.id] = it }
                }

                override fun referencesParent(parentId: Long) = customRows.values.any {
                    it.parentSystemKeywordId == parentId || it.pendingParentSystemKeywordId == parentId
                }
            }
            override val selections = object : SystemKeywordSelectionRepository {
                override fun findForUser(userId: Long) =
                    selectionRows.values.filter { it.userId == userId }.sortedBy { it.id }

                override fun save(
                    selection: SystemKeywordSelection, actorId: Long, now: LocalDateTime,
                ): SystemKeywordSelection {
                    check(!failWrites); writes++
                    return selection.copy(id = if (selection.id == 0L) next++ else selection.id)
                        .also { selectionRows[it.id] = it }
                }
            }
            override val locales = object : SystemKeywordLocaleRepository {
                override fun findAll() = localeRows.toList()
            }
        }
        try {
            return block(tx)
        } catch (failure: Throwable) {
            systemRows.clear(); systemRows.putAll(oldSystem)
            customRows.clear(); customRows.putAll(oldCustom)
            selectionRows.clear(); selectionRows.putAll(oldSelections)
            learnerIds.clear(); learnerIds.addAll(oldLearners)
            writes = oldWrites
            throw failure
        }
    }
}
