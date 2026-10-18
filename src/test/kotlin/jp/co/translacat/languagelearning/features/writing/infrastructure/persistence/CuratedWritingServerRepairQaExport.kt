package jp.co.translacat.languagelearning.features.writing.infrastructure.persistence

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import jp.co.translacat.languagelearning.features.writing.domain.policy.*
import jp.co.translacat.languagelearning.shared.ai.ModelExecutionCommand
import kotlinx.serialization.json.*
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** 명시한 합성 QA corpus를 읽어 실제 Kotlin 계약을 내보낸다. 실제 모델 응답이나 품질 통과를 만들지 않는다. */
internal object CuratedWritingServerRepairQaExport {
    fun commandIfRequested(command: ModelExecutionCommand) {
        val directoryName = System.getenv("LL_QA_SERVER_REPAIR_COMMANDS") ?: return
        val directory = Path.of(directoryName)
        require(directory.isAbsolute && Files.isDirectory(directory)) { "Explicit QA command directory must already exist" }
        val schemaName = requireNotNull(command.schemaName)
        require(schemaName in setOf("writing_observed_claims", "writing_atom_evidence_v14", "writing_server_repairs",
            "writing_server_repair_remaining", "writing_core_verification", "writing_optional_proposals", "writing_optional_verification"))
        val specimen = buildJsonObject {
            put("source", "ACTUAL_SYNTHETIC_TEST_MODEL_COMMAND"); put("providerCalls", 0)
            put("schemaName", schemaName); put("strict", command.strict); put("tier", command.tier.name)
            put("traceId", command.traceId); put("deadlineUtc", command.deadlineUtc.toString())
            put("maxOutputTokens", command.maxOutputTokens); put("verbosity", command.verbosity)
            put("taskName", command.taskName?.let(::JsonPrimitive) ?: JsonNull)
            put("instructions", command.instructions); put("responseSchema", command.responseSchema ?: JsonNull)
            put("messages", JsonArray(command.messages.map { message -> buildJsonObject {
                put("role", message.role); put("content", message.content)
            } }))
        }
        // 단계별 첫 합성 요청만 보존한다. 다른 테스트가 같은 단계를 실행해도 이미 쓴 원본을 덮어쓰지 않는다.
        try {
            Files.writeString(directory.resolve("$schemaName.json"), specimen.toString(), StandardOpenOption.CREATE_NEW)
        } catch (_: FileAlreadyExistsException) {
            // 이전 원본은 그대로 두며, 해당 파일을 현재 테스트의 새 산출물이라고 주장하지 않는다.
        }
    }

    fun verifyIfRequested() {
        val sourceName = System.getenv("LL_QA_SERVER_REPAIR_CASES") ?: return
        val outputName = requireNotNull(System.getenv("LL_QA_SERVER_REPAIR_CASE_REPORT")) {
            "LL_QA_SERVER_REPAIR_CASE_REPORT is required for an explicit QA export"
        }
        val source = Path.of(sourceName)
        val sourceBytes = Files.readAllBytes(source)
        val corpus = Json.parseToJsonElement(sourceBytes.toString(Charsets.UTF_8)).jsonObject
        val cases = corpus.getValue("cases").jsonArray.map { it.jsonObject }
        require(cases.size in setOf(32, 40) && cases.map { it.string("id") }.distinct().size == cases.size)
        val baseline = if (cases.size == 32) source else Path.of(requireNotNull(System.getenv("LL_QA_SERVER_REPAIR_BASELINE_CASES")))
        val baselineBytes = Files.readAllBytes(baseline)
        require(sha(baselineBytes) == "9c52dc7f2b18b9b2bd068e039edc6f2c4b183a88c8cde5e405b5ded51eb84f04") {
            "Original32 QA corpus fingerprint changed"
        }
        val baselineCases = Json.parseToJsonElement(baselineBytes.toString(Charsets.UTF_8)).jsonObject
            .getValue("cases").jsonArray.map { it.jsonObject }
        val actualById = cases.associateBy { it.string("id") }
        require(baselineCases.all { actualById[it.string("id")] == it }) { "Original32 QA case content changed" }

        // 실제 extraction을 제공했을 때만 meta-dependent 두 인자 schema를 내보낸다. 관측 결과를 합성해 대체하지 않는다.
        val extractionName = System.getenv("LL_QA_SERVER_REPAIR_ACTUAL_EXTRACTIONS")
        val extractionPath = extractionName?.let(Path::of)
        val extractions = extractionPath?.let { Json.parseToJsonElement(Files.readString(it)).jsonObject }
        val rows = cases.map { case ->
            val item = case.getValue("manifest").jsonObject.getValue("items").jsonArray.single().jsonObject
            val public = buildJsonObject {
                put("originText", item.getValue("prompt"))
                listOf("providedFacts", "requiredIntents", "responseConstraints").forEach { put(it, item.getValue(it)) }
                put("taskRequirements", item.getValue("checklist"))
            }
            val context = CuratedWritingFeedbackPolicy.Context(WritingType.valueOf(item.string("writingType")),
                public, item.string("originLanguage"), item.string("learningLanguage"))
            val answer = case.string("answer")
            val plan = requireNotNull(CuratedWritingFeedbackV14Policy.prepare(context,
                CuratedWritingFeedbackPolicy.requirements(item.getValue("checklist").jsonArray),
                CuratedWritingFeedbackPolicy.segments(answer))) { "No authored contract for frozen QA case ${case.string("id")}" }
            val extracted = extractions?.get(case.string("id"))?.let { CuratedWritingFeedbackV14Policy.validateExtraction(it, plan) }
            buildJsonObject {
                put("caseId", case.getValue("id")); put("answerSha256", sha(answer.toByteArray(Charsets.UTF_8)))
                put("meaningContractHash", plan.contract.sha256()); put("sourceRevisionHash", CuratedWritingEvidenceV14.sourceRevisionHash(plan))
                put("policyVersion", CuratedWritingFeedbackV14Policy.version)
                put("repairPlanVersion", CuratedWritingFeedbackV14Policy.repairPlanVersion)
                put("extractionInput", Json.parseToJsonElement(CuratedWritingFeedbackV14Policy.extractionPrompt(plan, answer)))
                put("extractionSchema", CuratedWritingFeedbackV14Policy.extractionSchema(plan))
                put("evidenceBaseSchema", CuratedWritingEvidenceV14.verificationSchema(plan))
                put("evidenceMetaDependentSchema", extracted?.let { CuratedWritingEvidenceV14.verificationSchema(plan, it) } ?: JsonNull)
                put("evidenceMetaDependentSchemaStatus", if (extracted == null) "NOT_RUN_REQUIRES_ACTUAL_EXTRACTION" else "EXPORTED_FROM_SUPPLIED_EXTRACTION")
                put("coreSchemaStatus", "NOT_RUN_REQUIRES_ACTUAL_VERIFIED_EVIDENCE")
            }
        }
        val result = buildJsonObject {
            put("sourceCorpusSha256", sha(sourceBytes)); put("original32Sha256", sha(baselineBytes))
            put("original32CasesUnchanged", true); put("cases", JsonArray(rows))
            put("actualExtractionsSha256", extractionPath?.let { JsonPrimitive(sha(Files.readAllBytes(it))) } ?: JsonNull)
            put("providerCalls", 0); put("qualityValidation", "NOT_PERFORMED_BY_EXPORTER")
        }
        val output = Path.of(outputName)
        // 원본과 이전 결과는 덮어쓰지 않는다. 제품·expected·누적 비용 ledger도 수정하지 않는다.
        require(!Files.exists(output)) { "QA export destination already exists; preserve the original report" }
        Files.writeString(output, result.toString(), StandardOpenOption.CREATE_NEW)
    }

    private fun sha(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
}
