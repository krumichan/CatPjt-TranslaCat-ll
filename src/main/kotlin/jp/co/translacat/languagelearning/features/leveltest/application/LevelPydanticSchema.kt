package jp.co.translacat.languagelearning.features.leveltest.application

import jp.co.translacat.languagelearning.shared.schema.PydanticSchema
import jp.co.translacat.languagelearning.shared.schema.PydanticSchemaFailure
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

internal data class LevelSchemaIssue(val path: String, val code: String)
internal class LevelSchemaFailure(val issues: List<LevelSchemaIssue>) : RuntimeException("LEVEL_TEST_SCHEMA_INVALID")

/** Level Test의 TypedDict 별칭 예외와 내부 정답 기본값은 기능 경계에 보존한다. */
internal object LevelPydanticSchema {
    private val decoder = PydanticSchema(
        nonAliasedTitles = setOf("LevelTestReferencePayload"),
        defaultObjectProperties = setOf("internalAnswerKey"),
    )

    fun decode(value: JsonElement, schema: JsonObject): JsonObject = try {
        decoder.decode(value, schema)
    } catch (failure: PydanticSchemaFailure) {
        throw LevelSchemaFailure(failure.issues.map { LevelSchemaIssue(it.path, it.code) })
    }
}
