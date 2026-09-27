package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*
import java.security.MessageDigest
import java.util.*

/** Python source_recovery.py의 TRANSLATION 원문 한 필드 제안 계약. */
internal object WritingSourceRecovery {
    data class Input(val candidateId: String, val contentHash: String, val draft: WritingCandidateDraft)
    data class Evidence(val originalText: String, val originalInputHash: String) {
        fun bindingHash(request: JsonObject, revised: WritingCandidateDraft): String = digest(
            buildJsonObject {
                put("originalInputHash", originalInputHash)
                put("originalText", originalText)
                put("repairedContentHash", WritingCandidatePolicy.contentHash(request, revised))
            },
        )

        fun payload(request: JsonObject, revised: WritingCandidateDraft): JsonObject = buildJsonObject {
            put("recoveryHash", bindingHash(request, revised))
            put(
                "originalSource",
                buildJsonObject {
                    put("id", "S0")
                    put("text", originalText)
                    put("learnerVisible", false)
                },
            )
        }
    }

    data class Proposal(val draft: WritingCandidateDraft, val evidence: Evidence)

    private val baseSchema: JsonObject by lazy {
        Json.parseToJsonElement(
            requireNotNull(
                javaClass.getResource(
                    "/writing/source-localization-base-schema.json",
                ),
            ).readText(),
        ).jsonObject
    }
    val instructions: String by lazy {
        requireNotNull(javaClass.getResource("/writing/source-localization-system-prompt.txt"))
            .readText().trimEnd().replace("\r\n", "\n")
    }

    fun input(candidateId: String, draft: WritingCandidateDraft): Input = Input(
        candidateId,
        digest(WritingDifficultyRepairPlan.draftJson(draft)), draft,
    )

    fun batchHash(inputs: List<Input>): String = digest(
        JsonArray(
            inputs.map { input ->
                buildJsonObject {
                    put("candidateId", input.candidateId)
                    put("contentHash", input.contentHash)
                    put("originText", input.draft.originText)
                }
            },
        ),
    )

    fun schema(request: JsonObject, targetBand: Int, inputs: List<Input>): JsonObject {
        require(inputs.size in 1..2)
        val spec = WritingDifficultyPolicy.spec(
            request.getValue("originLanguage").jsonPrimitive.content,
            jp.co.translacat.languagelearning.features.writing.domain.model.WritingType.TRANSLATION, targetBand,
        )

        // 원본 candidate ID와 내용 hash를 닫고 제안 필드만 현재 slot 길이와 문자에 제한한다.
        var schema = baseSchema.patch(listOf("properties", "items", "minItems"), JsonPrimitive(inputs.size))
        schema = schema.patch(listOf("properties", "items", "maxItems"), JsonPrimitive(inputs.size))
        schema = schema.patch(
            listOf("\$defs", "SourceProposal", "properties", "candidateId", "enum"),
            JsonArray(inputs.map { JsonPrimitive(it.candidateId) }),
        )
        schema = schema.patch(
            listOf("\$defs", "SourceProposal", "properties", "contentHash", "enum"),
            JsonArray(inputs.map { JsonPrimitive(it.contentHash) }),
        )
        val sourcePath = listOf("\$defs", "SourceProposal", "properties", "originText", "anyOf")
        val sourceOptions = schema.at(sourcePath).jsonArray.map { option ->
            if (option.jsonObject["type"]?.jsonPrimitive?.content != "string") option else {
                val pattern = sourcePattern(request.getValue("originLanguage").jsonPrimitive.content)
                JsonObject(
                    option.jsonObject + mapOf("maxLength" to JsonPrimitive(spec.originMaxCharacters)) +
                        (if (pattern == null) emptyMap() else mapOf("pattern" to JsonPrimitive(pattern))),
                )
            }
        }
        return schema.patch(sourcePath, JsonArray(sourceOptions))
    }

    fun prompt(request: JsonObject, batchId: String, inputs: List<Input>): String {
        val origin = request.getValue("originLanguage").jsonPrimitive.content
        val learning = request.getValue("learningLanguage").jsonPrimitive.content
        val payload = buildJsonObject {
            put("candidateId", batchId)
            put("contentHash", batchHash(inputs))
            put("sourceLanguageContract", WritingGenerationPrompt.sourceLanguageContract(origin, learning))
            put(
                "candidates",
                JsonArray(
                    inputs.map { input ->
                        buildJsonObject {
                            put("candidateId", input.candidateId)
                            put("contentHash", input.contentHash)
                            put("originText", input.draft.originText)
                        }
                    },
                ),
            )
        }
        return "<writing-source-data>\n" + WritingCandidatePolicy.canonicalJson(payload)
            .replace("<", "\\u003c").replace(">", "\\u003e") + "\n</writing-source-data>"
    }

    fun proposals(raw: JsonElement, batchId: String, contentHash: String, inputs: List<Input>): List<Proposal> {
        val batch = raw as? JsonObject ?: invalid("VERIFIER_RESPONSE_TYPE_INVALID")
        if (batch.keys != setOf("candidateId", "contentHash", "items")) invalid("VERIFIER_SCHEMA_INVALID")
        val returnedBatchId = string(batch, "candidateId")
        val returnedBatchHash = string(batch, "contentHash")
        if (returnedBatchId.length !in 1..100 || !hashPattern.matches(returnedBatchHash))
            invalid("VERIFIER_SCHEMA_INVALID")
        if (returnedBatchId != batchId) invalid("VERIFIER_IDENTITY_MISMATCH")
        if (returnedBatchHash != contentHash) invalid("VERIFIER_CONTENT_HASH_MISMATCH")
        val items = batch["items"] as? JsonArray ?: invalid("VERIFIER_SCHEMA_INVALID")
        if (items.size != inputs.size) invalid("VERIFIER_SOURCE_MAPPING_INVALID")
        val byId = inputs.associateBy { it.candidateId }
        val used = mutableSetOf<String>()
        val proposals = mutableListOf<Proposal>()

        // 응답을 원본 ID와 hash에 다시 결합하고 originText 외 필드는 원본에서 복사한다.
        for (entry in items) {
            val item = entry as? JsonObject ?: invalid("VERIFIER_SCHEMA_INVALID")
            if (item.keys != setOf("candidateId", "contentHash", "status", "originText"))
                invalid("VERIFIER_SCHEMA_INVALID")
            val id = string(item, "candidateId")
            val returnedHash = string(item, "contentHash")
            if (id.length !in 1..100 || !hashPattern.matches(returnedHash))
                invalid("VERIFIER_SCHEMA_INVALID")
            val input = byId[id] ?: invalid("VERIFIER_SOURCE_MAPPING_INVALID")
            if (!used.add(id)) invalid("VERIFIER_SOURCE_MAPPING_INVALID")
            if (returnedHash != input.contentHash) invalid("VERIFIER_CONTENT_HASH_MISMATCH")
            val status = string(item, "status")
            val source = item["originText"]
            if (status !in setOf("LOCALIZED", "UNRECOVERABLE") ||
                (status == "LOCALIZED") != (source is JsonPrimitive && source.isString)
            )
                invalid("SOURCE_STATUS_TEXT_MISMATCH")
            if (status == "UNRECOVERABLE") continue
            val text = (source as JsonPrimitive).content
            if (text.isBlank() || text.length > 2000) invalid("VERIFIER_SCHEMA_INVALID")
            if (text == input.draft.originText) continue
            proposals += Proposal(
                input.draft.copy(originText = text),
                Evidence(input.draft.originText, input.contentHash),
            )
        }
        if (used != byId.keys) invalid("VERIFIER_SOURCE_MAPPING_INVALID")
        return proposals
    }

    private fun sourcePattern(language: String): String? = when (
        language.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)
    ) {
        "ko" -> "[\\uac00-\\ud7a3\\u1100-\\u11ff]"
        "ja" -> "[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]"
        "en" -> "[A-Za-z]"
        else -> null
    }

    private fun digest(value: JsonElement): String = MessageDigest.getInstance("SHA-256")
        .digest(WritingCandidatePolicy.canonicalJson(value).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private val hashPattern = Regex("[a-f0-9]{64}")

    private fun string(value: JsonObject, name: String): String {
        val primitive = value[name] as? JsonPrimitive ?: invalid("VERIFIER_SCHEMA_INVALID")
        if (!primitive.isString) invalid("VERIFIER_SCHEMA_INVALID")
        return primitive.content
    }

    private fun JsonElement.at(path: List<String>): JsonElement =
        if (path.isEmpty()) this else jsonObject.getValue(path.first()).at(path.drop(1))

    private fun JsonObject.patch(path: List<String>, value: JsonElement): JsonObject {
        val head = path.first()
        val replacement = if (path.size == 1) value else
            getValue(head).jsonObject.patch(path.drop(1), value)
        return JsonObject(this + (head to replacement))
    }

    private fun invalid(code: String): Nothing = throw WritingReviewProtocolException(code)
}
