package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.application.CuratedWritingGenerationPersistence
import jp.co.translacat.languagelearning.features.writing.application.CuratedWritingGenerationPersistence.Claim
import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import jp.co.translacat.languagelearning.features.writing.infrastructure.persistence.table.*
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionResult
import jp.co.translacat.languagelearning.shared.persistence.transaction.JdbcTransactionRunner
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.jdbc.*
import java.time.*
import java.time.temporal.ChronoUnit
import java.util.UUID

internal class CuratedWritingGenerationStore(
    private val transactions: JdbcTransactionRunner,
    private val snapshots: CuratedWritingStore,
    private val releaseId: String,
    private val clock: Clock = Clock.systemUTC(),
) : CuratedWritingGenerationPersistence {
    override suspend fun prepare(user: Long, key: String, type: WritingType, band: Int, topic: String,
                                 repairOf: String?, repairNotes: String?): Claim = transactions.write {
        // 소유자 잠금으로 같은 preparation 키의 중복 실행을 직렬화한다.
        ExposedWritingSetUnitOfWork.lockCurrentOwner(user, now())
        val existing = find(user, key)
        val identity = buildJsonObject {
            put("writingType", type.name); put("targetBand", band); put("topic", topic); put("releaseId", releaseId)
            // 저장된 요청의 정책은 그대로 비교한다. v2 기동이 v1 성공 요청의 멱등성을 바꾸지 않는다.
            put("policyVersion", existing?.get(CuratedGenerationTable.policyVersion) ?: CuratedWritingGenerationPolicy.version)
            if (repairOf != null) { put("repairOfRequestKey", repairOf); put("repairNotes", repairNotes) }
        }
        val hash = CuratedWritingManifestCodec.sha256(CuratedWritingManifestCodec.canonical(identity))
        if (existing != null) {
            require(existing[CuratedGenerationTable.requestHash] == hash) { "WRITING_PREPARATION_CONFLICT" }
            return@write Claim(existing[CuratedGenerationTable.id], existing[CuratedGenerationTable.status], false,
                Json.parseToJsonElement(existing[CuratedGenerationTable.requestJson]).jsonObject)
        }
        // 결과를 아직 모르는 B2 요청이 있으면 다른 키로 같은 유한 코어를 중복 선택하지 않는다.
        if (type == WritingType.TRANSLATION && band == 2) {
            require(CuratedGenerationTable.selectAll().where {
                (CuratedGenerationTable.userId eq user) and
                    (CuratedGenerationTable.status inList listOf("RUNNING", "UNCERTAIN"))
            }.none { pending ->
                val pendingInput = Json.parseToJsonElement(pending[CuratedGenerationTable.requestJson]).jsonObject
                pendingInput["writingType"]?.jsonPrimitive?.content == "TRANSLATION" &&
                    pendingInput["targetBand"]?.jsonPrimitive?.int == 2
            }) { "WRITING_PREPARATION_CONFLICT" }
        }
        // 국소 수정은 같은 소유자의 저장 콘텐츠 한 건에서 한 번만 허용한다. 원본은 수정하지 않는다.
        var repairReasonCodes = emptyList<String>()
        val repairContent = if (repairOf != null) {
            val original = find(user, repairOf) ?: error("WRITING_PREPARATION_NOT_FOUND")
            val originalInput = Json.parseToJsonElement(original[CuratedGenerationTable.requestJson]).jsonObject
            require(original[CuratedGenerationTable.releaseId] == releaseId &&
                originalInput["writingType"]?.jsonPrimitive?.content == type.name &&
                originalInput["targetBand"]?.jsonPrimitive?.int == band &&
                originalInput["topic"]?.jsonPrimitive?.content == topic &&
                original[CuratedGenerationTable.status] in setOf("SUCCEEDED", "FAILED")) { "WRITING_PREPARATION_CONFLICT" }
            require("repairOfRequestKey" !in originalInput && CuratedGenerationTable.selectAll().where {
                CuratedGenerationTable.userId eq user
            }.none { candidate -> Json.parseToJsonElement(candidate[CuratedGenerationTable.requestJson]).jsonObject[
                "repairOfRequestKey"]?.jsonPrimitive?.content == repairOf }) { "WRITING_PREPARATION_REPAIR_LIMIT" }
            repairReasonCodes = reasonCodes(original, originalInput)
            original[CuratedGenerationTable.outputJson]?.let { Json.parseToJsonElement(it) as? JsonObject }
                ?: error("WRITING_PREPARATION_CONFLICT")
        } else null
        val storedOutputs = CuratedGenerationTable.selectAll().where { CuratedGenerationTable.userId eq user }
            .orderBy(CuratedGenerationTable.createdAt, SortOrder.DESC).map {
                it[CuratedGenerationTable.outputJson]?.let { raw -> Json.parseToJsonElement(raw) as? JsonObject }
            }
        // 일반 생성의 최근 12개 문맥은 유지하되, 유한 B2 코어 선택은 오래된 결과까지 중복 사용하지 않는다.
        val recentPrompts = storedOutputs.take(12).mapNotNull { (it?.get("prompt") as? JsonPrimitive)
            ?.takeIf { value -> value.isString }?.content }
        val recentCoreIds = storedOutputs.mapNotNull { (it?.get("coreId") as? JsonPrimitive)
            ?.takeIf { value -> value.isString }?.content }.toSet()
        val contentInput = CuratedWritingGenerationPolicy.input(type, band, topic, recentPrompts,
            repairContent, repairNotes, recentCoreIds,
            replaceCore = repairOf != null && type == WritingType.TRANSLATION && band == 2,
            repairReasonCodes = repairReasonCodes)
        val input = if (repairOf == null) contentInput else JsonObject(contentInput +
            ("repairOfRequestKey" to JsonPrimitive(repairOf)))
        val id = UUID.randomUUID().toString()
        CuratedGenerationTable.insert {
            it[CuratedGenerationTable.id] = id; it[userId] = user; it[requestKey] = key; it[requestHash] = hash
            it[requestJson] = input.toString(); it[CuratedGenerationTable.releaseId] = this@CuratedWritingGenerationStore.releaseId
            it[policyVersion] = CuratedWritingGenerationPolicy.version; it[catalogId] = "gen-$id"
            it[status] = "RUNNING"; it[createdAt] = now(); it[updatedAt] = now()
        }
        Claim(id, "RUNNING", true, input)
    }

    override suspend fun saveOutput(user: Long, key: String, result: ModelExecutionResult): Unit = transactions.write {
        ExposedWritingSetUnitOfWork.lockCurrentOwner(user, now())
        val row = find(user, key) ?: error("WRITING_PREPARATION_NOT_FOUND")
        if (row[CuratedGenerationTable.status] != "RUNNING") {
            require(row[CuratedGenerationTable.outputJson]?.let { Json.parseToJsonElement(it) } == result.output) {
                "WRITING_PREPARATION_CONFLICT"
            }
            return@write
        }
        require(result.provider == "openai" && (result.model == "gpt-5.6-luna" ||
            result.model.matches(Regex("gpt-5\\.6-luna-\\d{4}-\\d{2}-\\d{2}")))) { "WRITING_GENERATION_MODEL_MISMATCH" }
        CuratedGenerationTable.update({ CuratedGenerationTable.id eq row[CuratedGenerationTable.id] }) {
            it[outputJson] = result.output.toString(); it[status] = "GENERATED"
            it[provider] = result.provider; it[model] = result.model
            it[inputTokens] = result.inputTokens; it[outputTokens] = result.outputTokens; it[updatedAt] = now()
        }
    }

    override suspend fun finish(user: Long, key: String): JsonObject = transactions.write {
        ExposedWritingSetUnitOfWork.lockCurrentOwner(user, now())
        val row = find(user, key) ?: error("WRITING_PREPARATION_NOT_FOUND")
        if (row[CuratedGenerationTable.status] != "GENERATED") return@write render(row)
        val input = Json.parseToJsonElement(row[CuratedGenerationTable.requestJson]).jsonObject
        val manifest = try {
            CuratedWritingGenerationPolicy.assemble(Json.parseToJsonElement(checkNotNull(row[CuratedGenerationTable.outputJson])),
                row[CuratedGenerationTable.catalogId], row[CuratedGenerationTable.releaseId],
                WritingType.valueOf(input.getValue("writingType").jsonPrimitive.content),
                input.getValue("targetBand").jsonPrimitive.int, input.getValue("topic").jsonPrimitive.content, input)
        } catch (failure: IllegalArgumentException) {
            reject(row, failure.message?.takeIf { it.matches(Regex("WRITING_[A-Z_]+")) } ?: "WRITING_GENERATION_CONTENT_INVALID")
            return@write render(checkNotNull(find(user, key)))
        } catch (failure: IllegalStateException) {
            reject(row, failure.message?.takeIf { it.matches(Regex("WRITING_[A-Z_]+")) } ?: "WRITING_GENERATION_CONTENT_INVALID")
            return@write render(checkNotNull(find(user, key)))
        }

        // 응답을 저장한 요청에만 DRAFT를 연결한다. catalog와 성공 상태는 하나의 트랜잭션으로 반영한다.
        snapshots.persistGenerated(manifest)
        CuratedGenerationTable.update({ CuratedGenerationTable.id eq row[CuratedGenerationTable.id] }) {
            it[manifestJson] = manifest.toString(); it[status] = "SUCCEEDED"; it[updatedAt] = now()
        }
        render(checkNotNull(find(user, key)))
    }

    override suspend fun fail(user: Long, key: String, code: String, uncertain: Boolean) = transactions.write {
        ExposedWritingSetUnitOfWork.lockCurrentOwner(user, now())
        val row = find(user, key) ?: error("WRITING_PREPARATION_NOT_FOUND")
        if (row[CuratedGenerationTable.status] == "RUNNING") {
            CuratedGenerationTable.update({ CuratedGenerationTable.id eq row[CuratedGenerationTable.id] }) {
                it[status] = if (uncertain) "UNCERTAIN" else "FAILED"; it[failureCode] = code.take(80); it[updatedAt] = now()
            }
        }
    }

    override suspend fun get(user: Long, key: String): JsonObject = transactions.write {
        ExposedWritingSetUnitOfWork.lockCurrentOwner(user, now())
        render(find(user, key) ?: error("WRITING_PREPARATION_NOT_FOUND"))
    }

    private fun reject(row: ResultRow, code: String) {
        CuratedGenerationTable.update({ CuratedGenerationTable.id eq row[CuratedGenerationTable.id] }) {
            it[status] = "FAILED"; it[failureCode] = code; it[updatedAt] = now()
        }
    }
    private fun find(user: Long, key: String): ResultRow? = CuratedGenerationTable.selectAll().where {
        (CuratedGenerationTable.userId eq user) and (CuratedGenerationTable.requestKey eq key)
    }.singleOrNull()
    private fun render(row: ResultRow): JsonObject {
        val input = Json.parseToJsonElement(row[CuratedGenerationTable.requestJson]).jsonObject
        val status = row[CuratedGenerationTable.status]
        val core = input["writingType"]?.jsonPrimitive?.content == "TRANSLATION" &&
            input["targetBand"]?.jsonPrimitive?.int == 2
        val ownerRows = CuratedGenerationTable.selectAll().where {
            CuratedGenerationTable.userId eq row[CuratedGenerationTable.userId]
        }.toList()
        // 가능 여부는 현재 원본의 저장 상태와 한도에서 계산한다. 읽기에서 생성·복구를 실행하지 않는다.
        val blocked = when {
            "repairOfRequestKey" in input || ownerRows.any {
                Json.parseToJsonElement(it[CuratedGenerationTable.requestJson]).jsonObject[
                    "repairOfRequestKey"]?.jsonPrimitive?.content == row[CuratedGenerationTable.requestKey]
            } -> "REPAIR_LIMIT"
            status !in setOf("SUCCEEDED", "FAILED") -> "NOT_FINISHED"
            row[CuratedGenerationTable.outputJson]?.let { Json.parseToJsonElement(it) as? JsonObject } == null -> "NO_SAVED_CONTENT"
            core && CuratedWritingB2Core.replacementOptions(input.getValue("topic").jsonPrimitive.content,
                ownerRows.mapNotNull {
                    it[CuratedGenerationTable.outputJson]?.let { raw ->
                        ((Json.parseToJsonElement(raw) as? JsonObject)?.get("coreId") as? JsonPrimitive)
                            ?.takeIf { value -> value.isString }?.content
                    }
                }.toSet()).isEmpty() -> "CONTENT_EXHAUSTED"
            else -> null
        }
        val candidate = if (status == "SUCCEEDED") CuratedWritingManifestCodec.parse(
            checkNotNull(row[CuratedGenerationTable.manifestJson])).items.single() else null
        return buildJsonObject {
        put("requestKey", row[CuratedGenerationTable.requestKey]); put("preparationId", row[CuratedGenerationTable.id])
        put("status", row[CuratedGenerationTable.status]); put("policyVersion", row[CuratedGenerationTable.policyVersion])
        put("catalogId", row[CuratedGenerationTable.catalogId]); put("reviewStatus", "DRAFT"); put("qaOnly", true)
        put("failureCode", row[CuratedGenerationTable.failureCode]?.let(::JsonPrimitive) ?: JsonNull)
        put("provider", row[CuratedGenerationTable.provider]?.let(::JsonPrimitive) ?: JsonNull)
        put("model", row[CuratedGenerationTable.model]?.let(::JsonPrimitive) ?: JsonNull)
        put("requiresReconciliation", row[CuratedGenerationTable.status] in setOf("RUNNING", "UNCERTAIN"))
        put("writingType", input.getValue("writingType"))
        put("targetBand", input.getValue("targetBand"))
        put("topicKey", input.getValue("topic"))
        put("repairOfRequestKey", input["repairOfRequestKey"] ?: JsonNull)
        put("repairNotes", input["repairNotes"] ?: JsonNull)
        put("repairMode", if (core) "CORE_REPLACEMENT" else "CONTENT_REPAIR")
        put("canRepair", blocked == null)
        put("repairBlockedReason", blocked?.let(::JsonPrimitive) ?: JsonNull)
        put("quality", buildJsonObject {
            put("contractStatus", when (status) { "SUCCEEDED" -> "PASSED"; "FAILED" -> "FAILED"; else -> "PENDING" })
            put("semanticStatus", "NOT_REVIEWED")
            put("humanReview", "NOT_PERFORMED")
            put("releaseStatus", "QA_ONLY")
            put("reasonCodes", JsonArray(reasonCodes(row, input).map(::JsonPrimitive)))
        })
        // 첫 제출 전 응답에는 참고 답안·검사용 예시·실력 판정 근거를 포함하지 않는다.
        put("candidate", candidate?.let { item -> buildJsonObject {
            put("catalogId", item.id)
            put("contentHash", item.contentHash)
            put("writingType", item.writingType.name)
            put("targetBand", item.band)
            put("topicKey", item.topicKey)
            put("prompt", item.prompt)
            put("providedFacts", JsonArray(item.providedFacts.map(::JsonPrimitive)))
            put("requiredIntents", JsonArray(item.requiredIntents.map(::JsonPrimitive)))
            put("responseConstraints", JsonArray(item.responseConstraints.map(::JsonPrimitive)))
            put("visibleRequirements", JsonArray(item.visibleRequirements.map(::JsonPrimitive)))
        } } ?: JsonNull)
        }
    }

    private fun reasonCodes(row: ResultRow, input: JsonObject): List<String> = buildList {
        add("AUTOMATED_CHECK_ONLY")
        if (input["writingType"]?.jsonPrimitive?.content == "TRANSLATION" &&
            input["targetBand"]?.jsonPrimitive?.int == 2) add("CORE_CONTENT_FIXED")
        when (row[CuratedGenerationTable.status]) {
            "FAILED" -> add(if (row[CuratedGenerationTable.failureCode]?.let {
                it.startsWith("WRITING_GENERATION_") || it.startsWith("WRITING_B2_CORE_")
            } == true) "CONTENT_CHECK_FAILED" else "PROVIDER_FAILED")
            "RUNNING", "UNCERTAIN" -> add("EXECUTION_UNCERTAIN")
        }
    }
    private fun now(): LocalDateTime = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)
}
