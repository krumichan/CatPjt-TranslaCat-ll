package jp.co.translacat.languagelearning.features.keyword.application

import jp.co.translacat.languagelearning.features.keyword.domain.model.*
import jp.co.translacat.languagelearning.features.keyword.domain.policy.KeywordDisplayPolicy
import jp.co.translacat.languagelearning.features.keyword.domain.policy.KeywordPolicy
import java.time.LocalDate

/** 카탈로그/예약 상태의 유일한 쓰기 경로다. 숙련도 점수와 선택 횟수는 여기서 변경하지 않는다. */
internal class DefaultKeywordOperations(
    private val work: KeywordUnitOfWork,
    private val dates: KeywordLearningDate,
) : KeywordOperations {
    override suspend fun list(userId: Long, started: Boolean, locale: String?): KeywordList {
        requireUser(userId)
        val today = dates.today(userId)
        return work.execute(userId) {
            val catalog = system.findAll()
            val selectionsById = promoteSelections(userId, today, !started).associateBy { it.systemKeywordId }
            val display = KeywordDisplayPolicy.resolve(locales.findAll(), locale)
            val customRows = promoteCustom(userId, today, !started)
            KeywordList(
                catalog.filter { it.active }.map { keyword ->
                    val selection = selectionsById[keyword.id]
                    keyword.view(
                        catalog, selection?.desiredActive ?: false, selection?.pendingEffectiveDate,
                        display[keyword.id],
                    )
                },
                customRows.map { it.view(catalog) },
            )
        }
    }

    override suspend fun createCustom(userId: Long, started: Boolean, change: KeywordChange): KeywordView {
        requireUser(userId)
        KeywordPolicy.validate(change.text, change.type)
        val today = dates.today(userId)
        return work.execute(userId) {
            val catalog = system.findAll()
            val normalized = KeywordPolicy.normalize(change.text)
            val type = checkNotNull(change.type)
            validateCustomDuplicate(custom.findForUser(userId), null, normalized, type)
            val parent = parent(catalog, change.parentKeywordId)
            KeywordPolicy.hierarchy(type, parent, system = false)
            val effective = KeywordPolicy.effectiveDate(today, started)
            val value = CustomKeyword(
                userId = userId, text = KeywordPolicy.trim(checkNotNull(change.text)), normalizedText = normalized,
                type = type, canonicalKey = KeywordPolicy.canonical(change.canonicalKey, normalized),
                parentSystemKeywordId = parent?.id, active = false, availableFrom = effective,
                pendingActive = true, pendingEffectiveDate = effective,
            ).promote(today)
            custom.save(value, userId, nowUtc).view(catalog)
        }
    }

    override suspend fun updateCustom(
        userId: Long, started: Boolean, keywordId: Long, change: KeywordChange,
    ): KeywordView {
        requireUser(userId)
        val today = dates.today(userId)
        return work.execute(userId) {
            val all = custom.findForUser(userId)
            val original = all.firstOrNull { it.id == keywordId } ?: throw KeywordPolicy.notFound()
            val keyword = original.promote(today)
            val text = change.text ?: keyword.desiredText
            val type = change.type ?: keyword.desiredType
            KeywordPolicy.validate(text, type)
            val normalized = KeywordPolicy.normalize(text)
            validateCustomDuplicate(all, keywordId, normalized, type)
            val catalog = system.findAll()
            // 기존 PATCH도 부모 필드가 없으면 null로 처리한다. 보존으로 바꾸지 않는다.
            val parent = parent(catalog, change.parentKeywordId)
            KeywordPolicy.hierarchy(type, parent, system = false)
            val value = keyword.schedule(
                KeywordPolicy.trim(text), normalized, type,
                KeywordPolicy.canonical(change.canonicalKey, keyword.desiredCanonicalKey ?: normalized),
                parent?.id, change.active, KeywordPolicy.effectiveDate(today, started),
            ).promote(today)
            custom.save(value, userId, nowUtc).view(catalog)
        }
    }

    override suspend fun deleteCustom(userId: Long, started: Boolean, keywordId: Long) {
        requireUser(userId)
        val today = dates.today(userId)
        work.execute(userId) {
            val keyword = (custom.findForUser(userId).firstOrNull { it.id == keywordId }
                ?: throw KeywordPolicy.notFound()).promote(today)
            // 원본 deactivate는 텍스트/유형은 활성값, 부모는 예약 의도를 사용한다.
            val value = keyword.schedule(
                keyword.text, keyword.normalizedText, keyword.type, keyword.canonicalKey,
                keyword.desiredParentSystemKeywordId, false, KeywordPolicy.effectiveDate(today, started),
            ).promote(today)
            custom.save(value, userId, nowUtc)
        }
    }

    override suspend fun selectSystem(userId: Long, started: Boolean, keywordId: Long, selected: Boolean): KeywordView {
        requireUser(userId)
        val today = dates.today(userId)
        return work.execute(userId) {
            val catalog = system.findAll()
            val keyword = catalog.firstOrNull { it.id == keywordId && it.active } ?: throw KeywordPolicy.notFound()
            val effective = KeywordPolicy.effectiveDate(today, started)
            val selection = (selections.findForUser(userId).firstOrNull { it.systemKeywordId == keywordId }
                ?: SystemKeywordSelection(
                    userId = userId,
                    systemKeywordId = keywordId,
                    active = false,
                    availableFrom = effective,
                    pendingActive = true,
                    pendingEffectiveDate = effective,
                )).promote(today).copy(pendingActive = selected, pendingEffectiveDate = effective).promote(today)
            val saved = selections.save(selection, userId, nowUtc)
            keyword.view(catalog, saved.desiredActive, saved.pendingEffectiveDate)
        }
    }

    override suspend fun listSystem(): List<KeywordView> = work.execute(null) {
        val catalog = system.findAll()
        catalog.map { it.view(catalog) }
    }

    override suspend fun createSystem(actorId: Long, change: KeywordChange): KeywordView {
        requireUser(actorId)
        KeywordPolicy.validate(change.text, change.type)
        return work.execute(null) {
            val catalog = system.findAll()
            val normalized = KeywordPolicy.normalize(change.text)
            val type = checkNotNull(change.type)
            validateSystemDuplicate(catalog, null, normalized, type)
            val parent = parent(catalog, change.parentKeywordId)
            KeywordPolicy.hierarchy(type, parent, system = true)
            val value = SystemKeyword(
                text = KeywordPolicy.trim(checkNotNull(change.text)), normalizedText = normalized, type = type,
                canonicalKey = KeywordPolicy.canonical(change.canonicalKey, normalized), parentKeywordId = parent?.id,
                sortOrder = KeywordPolicy.sortOrder(change.sortOrder, 0),
            )
            system.save(value, actorId, nowUtc).view(catalog)
        }
    }

    override suspend fun updateSystem(actorId: Long, keywordId: Long, change: KeywordChange): KeywordView {
        requireUser(actorId)
        return work.execute(null) {
            val catalog = system.findAll()
            val keyword = catalog.firstOrNull { it.id == keywordId } ?: throw KeywordPolicy.notFound()
            val text = change.text ?: keyword.text
            val type = change.type ?: keyword.type
            KeywordPolicy.validate(text, type)
            val normalized = KeywordPolicy.normalize(text)
            validateSystemDuplicate(catalog, keywordId, normalized, type)
            val parent = parent(catalog, change.parentKeywordId)
            KeywordPolicy.hierarchy(type, parent, system = true, currentId = keywordId)
            val customReference = custom.referencesParent(keywordId)
            val children = catalog.filter { it.parentKeywordId == keywordId }
            if (type != KeywordType.TOPIC && (children.isNotEmpty() || customReference)) throw KeywordPolicy.invalidHierarchy()
            if (change.active == false && (children.any { it.active } || customReference)) throw KeywordPolicy.invalidHierarchy()
            val value = keyword.copy(
                text = KeywordPolicy.trim(text), normalizedText = normalized, type = type,
                canonicalKey = KeywordPolicy.canonical(change.canonicalKey, keyword.canonicalKey ?: normalized),
                parentKeywordId = parent?.id, sortOrder = KeywordPolicy.sortOrder(change.sortOrder, keyword.sortOrder),
                active = change.active ?: keyword.active,
            )
            system.save(value, actorId, nowUtc).view(catalog)
        }
    }

    override suspend fun candidates(userId: Long, started: Boolean, date: LocalDate): List<KeywordCandidate> {
        requireUser(userId)
        return work.execute(userId) {
            val catalog = system.findAll().associateBy { it.id }
            val customCandidates = promoteCustom(userId, date, !started).filter { it.active }.map {
                KeywordCandidate(
                    "CUSTOM:" + it.id, it.text, KeywordSource.CUSTOM, it.type, it.canonicalKey, it.availableFrom,
                )
            }
            val systemCandidates =
                promoteSelections(userId, date, !started).filter { it.active }.mapNotNull { selection ->
                    catalog[selection.systemKeywordId]?.takeIf { it.active }?.let {
                        KeywordCandidate(
                            "SYSTEM:" + it.id,
                            it.text,
                            KeywordSource.SYSTEM,
                            it.type,
                            it.canonicalKey,
                            selection.availableFrom,
                        )
                    }
                }
            customCandidates + systemCandidates
        }
    }

    private fun KeywordTransaction.promoteCustom(userId: Long, date: LocalDate, immediately: Boolean) =
        custom.findForUser(userId).map { old ->
            val value = old.promote(date, immediately)
            if (value != old) custom.save(value, userId, nowUtc) else old
        }

    private fun KeywordTransaction.promoteSelections(userId: Long, date: LocalDate, immediately: Boolean) =
        selections.findForUser(userId).map { old ->
            val value = old.promote(date, immediately)
            if (value != old) selections.save(value, userId, nowUtc) else old
        }

    private fun parent(catalog: List<SystemKeyword>, id: Long?): SystemKeyword? =
        if (id == null) null else catalog.firstOrNull { it.id == id } ?: throw KeywordPolicy.invalidHierarchy()

    private fun validateSystemDuplicate(
        all: List<SystemKeyword>, excluded: Long?, normalized: String, type: KeywordType,
    ) {
        if (all.any { it.id != excluded && it.normalizedText == normalized && it.type == type }) throw KeywordPolicy.duplicated()
    }

    private fun validateCustomDuplicate(
        all: List<CustomKeyword>, excluded: Long?, normalized: String, type: KeywordType,
    ) {
        if (all.any { it.id != excluded && it.desiredNormalizedText == normalized && it.desiredType == type }) throw KeywordPolicy.duplicated()
    }

    private fun requireUser(id: Long) {
        require(id > 0) { "사용자 ID는 양수여야 합니다." }
    }

    private fun SystemKeyword.view(
        catalog: List<SystemKeyword>,
        selected: Boolean = false,
        pending: LocalDate? = null,
        display: KeywordDisplayName? = null,
    ) = KeywordView(
        id,
        text,
        display?.primary ?: text,
        display?.secondary,
        KeywordSource.SYSTEM,
        type,
        canonicalKey,
        parentKeywordId,
        catalog.firstOrNull { it.id == parentKeywordId }?.canonicalKey,
        sortOrder,
        active,
        selected,
        pending,
    )

    private fun CustomKeyword.view(catalog: List<SystemKeyword>) = KeywordView(
        id, desiredText, null, null, KeywordSource.CUSTOM, desiredType, desiredCanonicalKey,
        desiredParentSystemKeywordId, catalog.firstOrNull { it.id == desiredParentSystemKeywordId }?.canonicalKey,
        0, desiredActive, desiredActive, pendingEffectiveDate,
    )
}
