package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** 새 기본 정책과 무관하게 이미 저장된 V13 PENDING 작업의 경로를 검증하는 실제 DB fixture다. */
internal object CuratedWritingLegacySemanticSeed {
    suspend fun pending(runner: JdbcTransactionRunner, snapshots: CuratedWritingStore,
                        request: CuratedWritingStore.Start, itemId: Long, answerText: String,
                        revision: String, clock: Clock) {
        // 준비: 답안은 정식 저장 경로로 만들되 아직 feedback worker는 연결하지 않는다.
        snapshots.submit(request.userId, itemId, answerText, revision, request.date, request.reviewDays,
            "curated-writing-variable-n-v1")
        runner.write {
            val answer = CuratedAnswersTable.selectAll().where {
                (CuratedAnswersTable.userId eq request.userId) and (CuratedAnswersTable.itemId eq itemId)
            }.single()
            val item = assertNotNull(snapshots.findItem(request.userId, itemId))
            val set = assertNotNull(snapshots.findSetById(request.userId, item[CuratedItemsTable.setId]))
            val public = Json.parseToJsonElement(item[CuratedItemsTable.publicJson]).jsonObject
            val reference = Json.parseToJsonElement(item[CuratedItemsTable.privateJson]).jsonObject
            val context = CuratedWritingFeedbackPolicy.Context(WritingType.valueOf(set[CuratedSetsTable.writingType]),
                public, set[CuratedSetsTable.originLanguage], set[CuratedSetsTable.learningLanguage])
            val meaning = assertNotNull(CuratedWritingFeedbackV13Policy.prepare(context,
                CuratedWritingFeedbackPolicy.requirements(reference.getValue("checklist").jsonArray),
                CuratedWritingFeedbackPolicy.segments(answerText)))
            val answerId = answer[CuratedAnswersTable.id]
            val answerHash = CuratedWritingManifestCodec.sha256(answerText)
            val sourceHash = CuratedWritingManifestCodec.sha256("${item[CuratedItemsTable.contentRevision]}|" +
                "${item[CuratedItemsTable.publicJson]}|${item[CuratedItemsTable.privateJson]}")
            val requestHash = CuratedWritingManifestCodec.sha256("${request.userId}|$answerId|$answerHash|$sourceHash|" +
                "curated-writing-feedback-v13|${meaning.contract.sha256()}")
            val now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)

            // 실행: 원래 V13 identity와 PENDING 상태를 넣는다. 기대 호출 수나 기존 검증기는 바꾸지 않는다.
            CuratedWritingFeedbackTable.insert {
                it[CuratedWritingFeedbackTable.answerId] = answerId
                it[CuratedWritingFeedbackTable.itemId] = itemId
                it[setId] = set[CuratedSetsTable.id]; it[userId] = request.userId
                it[policyVersion] = "curated-writing-feedback-v13"; it[status] = "PENDING"
                it[CuratedWritingFeedbackTable.answerHash] = answerHash; it[contentRevision] = revision
                it[CuratedWritingFeedbackTable.sourceHash] = sourceHash; it[CuratedWritingFeedbackTable.requestHash] = requestHash
                it[attemptCount] = 0; it[createdAt] = now; it[updatedAt] = now
            }
            assertEquals("PENDING", CuratedWritingFeedbackTable.selectAll().where {
                CuratedWritingFeedbackTable.answerId eq answerId
            }.single()[CuratedWritingFeedbackTable.status])
        }
    }
}
