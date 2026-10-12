package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.growth.application.GrowthProjector
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthActivity
import jp.co.translacat.languagelearning.features.growth.domain.model.GrowthChange
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.LocalDateTime

/** 한 계획을 한 비점수 활동으로 기록한다. 호출자는 owner 잠금을 가진 답안/계획 트랜잭션이다. */
internal object CuratedWritingActivity {
    fun referenceId(setId: Long) = "curated-plan:${LearningPublicId.encode(setId)}"

    fun requireActive(userId: Long) {
        ExposedWritingSetUnitOfWork.requireCurrentOwnerActive(userId)
    }

    private fun repository() = ExposedWritingSetUnitOfWork.currentGrowthRecords()

    fun synchronize(set: ResultRow, now: LocalDateTime, allowCreate: Boolean = false) {
        val userId = set[CuratedSetsTable.userId]
        val setId = set[CuratedSetsTable.id]
        val records = repository()
        val old = records.activity(userId, "WRITING", referenceId(setId))
        // 이전 QA/사용자 결과는 조회·중복 제출·목표 변경만으로 일괄 소급 생성하지 않는다.
        if (old == null && !allowCreate) return
        val items = CuratedItemsTable.selectAll().where {
            (CuratedItemsTable.userId eq userId) and (CuratedItemsTable.setId eq setId)
        }.map { it[CuratedItemsTable.id] }
        if (items.isEmpty()) return
        val answers = CuratedAnswersTable.selectAll().where {
            (CuratedAnswersTable.userId eq userId) and (CuratedAnswersTable.itemId inList items)
        }.toList()
        if (answers.isEmpty()) return

        // 최초 접수 시점과 저장된 학습일/언어가 활동의 정체성이다. 모델 대기 시간은 학습 시간으로 쓰지 않는다.
        val submitted = answers.map { it[CuratedAnswersTable.itemId] }.distinct().size
        val target = set[CuratedSetsTable.targetItemCount]
        val completed = submitted == target && items.size == target && set[CuratedSetsTable.status] == "COMPLETED"
        val metadata = buildJsonObject {
            put("activityUnit", "PLAN")
            put("planId", LearningPublicId.encode(setId))
            put("policyVersion", set[CuratedSetsTable.policyVersion])
            put("resultPolicy", set[CuratedSetsTable.resultPolicy])
            put("writingType", set[CuratedSetsTable.writingType])
            put("originLanguage", set[CuratedSetsTable.originLanguage])
            put("learningLanguage", set[CuratedSetsTable.learningLanguage])
            put("targetItemCount", target)
            put("acquiredItemCount", items.size)
            put("submittedItemCount", submitted)
            put("durationStatus", "NOT_MEASURED")
        }.toString()
        val fact = GrowthActivity(
            id = old?.id ?: 0L, userId = userId, source = "WRITING", referenceId = referenceId(setId),
            learningDate = set[CuratedSetsTable.learningDate], title = "Writing ${set[CuratedSetsTable.writingType]}",
            durationSeconds = 0, status = if (completed) "COMPLETED" else "IN_PROGRESS",
            startedAt = old?.startedAt ?: answers.minOf { it[CuratedAnswersTable.submittedAt] },
            completedAt = if (completed) old?.completedAt ?: now else null,
            metadataJson = metadata, createdAt = old?.createdAt ?: now, updatedAt = old?.updatedAt ?: now,
        )

        // 재전송/GET/피드백 재시도는 활동 수와 조회 revision을 늘리지 않는다. metric/profile 경로는 사용하지 않는다.
        if (old == fact) return
        GrowthProjector(records).apply(userId, GrowthChange.ActivityRecorded(fact, null), now)
    }

    fun delete(userId: Long, setId: Long) {
        val records = repository()
        val old = records.activity(userId, "WRITING", referenceId(setId)) ?: return
        check(old.overallScore == null && records.metrics(old.id).isEmpty()) { "WRITING_ACTIVITY_POLICY_CONFLICT" }
        records.deleteUnscoredActivity(userId, "WRITING", referenceId(setId))
    }

    fun publicState(userId: Long, setId: Long): JsonElement {
        val activity = repository().activity(userId, "WRITING", referenceId(setId)) ?: return JsonNull
        return buildJsonObject {
            put("referenceId", activity.referenceId)
            put("status", activity.status)
            put("learningDate", activity.learningDate.toString())
            put("metadata", Json.parseToJsonElement(activity.metadataJson))
        }
    }
}
