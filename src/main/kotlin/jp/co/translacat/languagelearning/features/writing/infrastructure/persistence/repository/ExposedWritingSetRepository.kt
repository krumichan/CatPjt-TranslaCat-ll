package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.repository

import jp.co.translacat.languagelearning.features.writing.domain.model.*
import jp.co.translacat.languagelearning.features.writing.domain.repository.WritingSetRepository
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.CuratedSetsTable
import jp.co.translacat.languagelearning.features.growth.domain.policy.GrowthLanguageScope
import jp.co.translacat.languagelearning.features.settings.domain.model.ConfiguredLanguagePair
import jp.co.translacat.languagelearning.features.settings.domain.repository.UserSettingsRepository
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.LocalDate
import java.time.LocalDateTime
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.WritingSetsTable as Sets

internal class ExposedWritingSetRepository(
    private val requireTransaction: () -> Unit,
    private val settings: UserSettingsRepository? = null,
    private val explicitScope: String? = null,
) : WritingSetRepository {
    override fun scoped(originLanguage: String?, learningLanguage: String?): WritingSetRepository {
        requireTransaction()
        val key = if (originLanguage == null && learningLanguage == null) ""
        else GrowthLanguageScope.key(checkNotNull(originLanguage), checkNotNull(learningLanguage))
        return ExposedWritingSetRepository(requireTransaction, settings, key)
    }

    private fun activeScope(userId: Long): String {
        explicitScope?.let { return it }
        val setting = settings?.findForUser(userId)
        val origin = setting?.originLanguage
        val learning = setting?.learningLanguage
        return if (origin != null && learning != null) GrowthLanguageScope.key(origin, learning) else ""
    }
    override fun existsForUser(userId: Long): Boolean {
        requireTransaction()
        return !Sets.selectAll().where { Sets.userId eq userId }.limit(1).empty()
    }

    override fun recoverable(nowUtc: LocalDateTime, limit: Int): List<WritingGenerationJob> {
        requireTransaction()
        require(limit in 1..100)
        return Sets.selectAll().where {
            (Sets.status eq WritingSetStatus.GENERATING.name) and
                ((Sets.generationLeaseUntil.isNull()) or (Sets.generationLeaseUntil lessEq nowUtc))
        }.orderBy(Sets.id).limit(limit).map { WritingGenerationJob(it[Sets.id], it[Sets.userId]) }
    }

    override fun find(userId: Long, date: LocalDate, type: WritingType): WritingSet? {
        requireTransaction()
        return Sets.selectAll().where {
            (Sets.userId eq userId) and (Sets.learningDate eq date) and (Sets.writingType eq type.name) and
                (Sets.languageScope eq activeScope(userId))
        }.singleOrNull()?.let(::toModel)
    }

    override fun findById(userId: Long, setId: Long): WritingSet? {
        requireTransaction()
        return Sets.selectAll().where { (Sets.userId eq userId) and (Sets.id eq setId) }.singleOrNull()?.let(::toModel)
    }

    override fun create(value: NewWritingSet, nowUtc: LocalDateTime): WritingSet {
        requireTransaction()
        val snapshot = Json.parseToJsonElement(value.snapshotJson) as? JsonObject
        fun language(name: String) = (snapshot?.get(name) as? JsonPrimitive)?.takeIf { it.isString }
            ?.content?.takeIf { it.trim().length in 2..20 }
        val origin = language("originLanguage")
        val learning = language("learningLanguage")
        val pair = if (origin != null && learning != null) ConfiguredLanguagePair(origin, learning) else null

        // 두 Writing 경로가 같은 owner 잠금을 공유한다. 새 경로의 날짜·유형이 이미 있으면 중복 생성하지 않는다.
        require(CuratedSetsTable.selectAll().where {
            (CuratedSetsTable.userId eq value.userId) and
                (CuratedSetsTable.learningDate eq value.learningDate) and
                (CuratedSetsTable.writingType eq value.writingType.name)
        }.none { row -> pair == null || pair.matches(row[CuratedSetsTable.originLanguage], row[CuratedSetsTable.learningLanguage]) }) {
            "WRITING_POLICY_CONFLICT"
        }

        val id = Sets.insert {
            it[userId] = value.userId
            it[learningDate] = value.learningDate
            it[languageScope] = pair?.let { GrowthLanguageScope.key(it.originLanguage, it.learningLanguage) } ?: ""
            it[writingType] = value.writingType.name
            it[snapshotId] = value.snapshotId
            it[sentenceCount] = value.sentenceCount
            it[status] = WritingSetStatus.GENERATING.name
            it[snapshotJson] = value.snapshotJson
            it[regenerationCount] = 0
            it[generationLeaseUntil] = nowUtc
            it[createdAt] = nowUtc
            it[updatedAt] = nowUtc
        }[Sets.id]
        return checkNotNull(findById(value.userId, id))
    }

    override fun claimGeneration(
        userId: Long, setId: Long, token: String, nowUtc: LocalDateTime, leaseUntil: LocalDateTime,
    ): Boolean {
        requireTransaction()
        return Sets.update(
            {
                (Sets.id eq setId) and (Sets.userId eq userId) and
                    (Sets.status eq WritingSetStatus.GENERATING.name) and
                    ((Sets.generationLeaseUntil.isNull()) or (Sets.generationLeaseUntil lessEq nowUtc))
            },
        ) {
            it[generationToken] = token
            it[generationLeaseUntil] = leaseUntil
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun releaseGeneration(
        userId: Long, setId: Long, token: String, promptVersion: String, nowUtc: LocalDateTime,
    ): Boolean {
        requireTransaction()
        return Sets.update(
            {
                (Sets.id eq setId) and (Sets.userId eq userId) and
                    (Sets.status eq WritingSetStatus.GENERATING.name) and (Sets.generationToken eq token)
            },
        ) {
            it[Sets.promptVersion] = promptVersion
            it[generationToken] = null
            it[generationLeaseUntil] = nowUtc
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun failGeneration(
        userId: Long, setId: Long, token: String, message: String, hasItems: Boolean, nowUtc: LocalDateTime,
    ): Boolean {
        requireTransaction()
        return Sets.update(
            {
                (Sets.id eq setId) and (Sets.userId eq userId) and
                    (Sets.status eq WritingSetStatus.GENERATING.name) and (Sets.generationToken eq token)
            },
        ) {
            it[status] = if (hasItems) WritingSetStatus.PARTIAL.name else WritingSetStatus.FAILED.name
            it[failureMessage] = message
            it[generationToken] = null
            it[generationLeaseUntil] = nowUtc
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun restartGeneration(userId: Long, setId: Long, nowUtc: LocalDateTime): Boolean {
        requireTransaction()
        return Sets.update(
            {
                (Sets.id eq setId) and (Sets.userId eq userId) and
                    (Sets.status inList listOf(WritingSetStatus.PARTIAL.name, WritingSetStatus.FAILED.name))
            },
        ) {
            it[status] = WritingSetStatus.GENERATING.name
            it[failureMessage] = null
            it[generationToken] = null
            it[generationLeaseUntil] = nowUtc
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun markReady(userId: Long, setId: Long, token: String?, nowUtc: LocalDateTime): Boolean {
        requireTransaction()
        return Sets.update(
            {
                (Sets.id eq setId) and (Sets.userId eq userId) and
                    (Sets.status eq WritingSetStatus.GENERATING.name) and
                    (if (token == null) Sets.generationToken.isNull() else Sets.generationToken eq token)
            },
        ) {
            it[status] = WritingSetStatus.READY.name
            it[failureMessage] = null
            it[generationToken] = null
            it[generationLeaseUntil] = nowUtc
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun claimRegeneration(
        userId: Long, setId: Long, token: String, nowUtc: LocalDateTime, leaseUntil: LocalDateTime,
    ): Boolean {
        requireTransaction()
        return Sets.update(
            {
                (Sets.id eq setId) and (Sets.userId eq userId) and
                    (Sets.status inList listOf(WritingSetStatus.READY.name, WritingSetStatus.COMPLETED.name)) and
                    (Sets.regenerationCount less 3) and
                    ((Sets.generationToken.isNull()) or (Sets.generationLeaseUntil.isNull()) or
                        (Sets.generationLeaseUntil lessEq nowUtc))
            },
        ) {
            it[generationToken] = token
            it[generationLeaseUntil] = leaseUntil
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun finishRegeneration(
        userId: Long, setId: Long, token: String, increment: Boolean, nowUtc: LocalDateTime,
    ): Boolean {
        requireTransaction()
        return Sets.update(
            {
                (Sets.id eq setId) and (Sets.userId eq userId) and
                    (Sets.status inList listOf(WritingSetStatus.READY.name, WritingSetStatus.COMPLETED.name)) and
                    (Sets.generationToken eq token)
            },
        ) {
            if (increment) it[regenerationCount] = Sets.regenerationCount + 1
            it[generationToken] = null
            it[generationLeaseUntil] = nowUtc
            it[updatedAt] = nowUtc
        } == 1
    }

    override fun complete(userId: Long, setId: Long, nowUtc: LocalDateTime): Boolean {
        requireTransaction()
        return Sets.update(
            {
                (Sets.id eq setId) and (Sets.userId eq userId) and (Sets.status eq WritingSetStatus.READY.name)
            },
        ) {
            it[status] = WritingSetStatus.COMPLETED.name
            it[completedAt] = nowUtc
            it[updatedAt] = nowUtc
        } == 1
    }

    private fun toModel(row: ResultRow) = WritingSet(
        id = row[Sets.id], userId = row[Sets.userId], learningDate = row[Sets.learningDate],
        writingType = WritingType.valueOf(row[Sets.writingType]), snapshotId = row[Sets.snapshotId],
        sentenceCount = row[Sets.sentenceCount], status = WritingSetStatus.valueOf(row[Sets.status]),
        snapshotJson = row[Sets.snapshotJson], promptVersion = row[Sets.promptVersion],
        regenerationCount = row[Sets.regenerationCount], generationToken = row[Sets.generationToken],
        generationLeaseUntil = row[Sets.generationLeaseUntil], failureMessage = row[Sets.failureMessage],
    )
}
