package jp.co.translacat.languagelearning.features.writing.domain.policy

import jp.co.translacat.languagelearning.features.writing.domain.model.WritingType
import kotlinx.serialization.json.*
import java.security.MessageDigest

internal data class CuratedWritingManifest(val releaseId: String, val items: List<CuratedWritingItem>)

/** manifest의 정확한 내용 hash를 검사하고 미지 필드를 거절한다. 상태는 승인 자료와 분리한다. */
internal object CuratedWritingManifestCodec {
    private val rootKeys = setOf("releaseId", "items")
    private val itemKeys = setOf(
        "id", "version", "contentHash", "status", "releaseId", "originLanguage", "learningLanguage",
        "writingType", "band", "semanticKey", "topicKey", "prompt", "providedFacts", "requiredIntents",
        "responseConstraints", "checklist", "referenceAnswers", "alternativeNote", "levelEvidence",
        "validAlternative", "clearError", "boundaryAnswer",
    )

    fun parse(source: String): CuratedWritingManifest {
        val root = Json.parseToJsonElement(source).jsonObject
        require(root.keys == rootKeys) { "WRITING_MANIFEST_SCHEMA_INVALID" }
        val releaseId = root.getValue("releaseId").jsonPrimitive.content
        require(releaseId.matches(Regex("[a-z0-9][a-z0-9-]{2,79}"))) { "WRITING_RELEASE_INVALID" }
        val values = root.getValue("items").jsonArray
        require(values.isNotEmpty()) { "WRITING_MANIFEST_EMPTY" }
        val items = values.map { raw ->
            val obj = raw.jsonObject
            require(obj.keys == itemKeys) { "WRITING_ITEM_SCHEMA_INVALID" }
            val hash = obj.getValue("contentHash").jsonPrimitive.content
            val withoutHash = JsonObject(obj.filterKeys { it != "contentHash" && it != "status" })
            require(hash == sha256(canonical(withoutHash))) { "WRITING_CONTENT_HASH_MISMATCH" }
            val item = CuratedWritingItem(
                id = obj.string("id"), version = obj.getValue("version").jsonPrimitive.int,
                contentHash = hash, status = CuratedReviewStatus.valueOf(obj.string("status")),
                releaseId = obj.string("releaseId"), originLanguage = obj.string("originLanguage"),
                learningLanguage = obj.string("learningLanguage"),
                writingType = WritingType.valueOf(obj.string("writingType")),
                band = obj.getValue("band").jsonPrimitive.int, semanticKey = obj.string("semanticKey"),
                topicKey = obj.string("topicKey"), prompt = obj.string("prompt"),
                providedFacts = obj.strings("providedFacts"), requiredIntents = obj.strings("requiredIntents"),
                responseConstraints = obj.strings("responseConstraints"), checklist = obj.strings("checklist"),
                referenceAnswers = obj.strings("referenceAnswers"), alternativeNote = obj.string("alternativeNote"),
                levelEvidence = obj.string("levelEvidence"), validAlternative = obj.string("validAlternative"),
                clearError = obj.string("clearError"), boundaryAnswer = obj.string("boundaryAnswer"),
            )
            require(item.releaseId == releaseId) { "WRITING_RELEASE_MISMATCH" }
            require(item.originLanguage == "ko" && item.learningLanguage == "ja") { "WRITING_LANGUAGE_UNSUPPORTED" }
            item
        }
        require(items.map { it.id to it.version }.distinct().size == items.size) { "WRITING_ITEM_DUPLICATE" }
        return CuratedWritingManifest(releaseId, items)
    }

    fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    fun canonical(value: JsonElement): String = when (value) {
        is JsonObject -> value.keys.sorted().joinToString(prefix = "{", postfix = "}", separator = ",") { key ->
            "${Json.encodeToString(JsonPrimitive(key))}:${canonical(value.getValue(key))}"
        }
        is JsonArray -> value.joinToString(prefix = "[", postfix = "]", separator = ",") { canonical(it) }
        else -> value.toString()
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content
    private fun JsonObject.strings(key: String): List<String> = getValue(key).jsonArray.map { it.jsonPrimitive.content }
}
