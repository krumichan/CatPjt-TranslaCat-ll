package jp.co.translacat.languagelearning.shared.schema

import kotlinx.serialization.json.*
import java.math.BigDecimal

internal data class PydanticSchemaIssue(val path: String, val code: String)
internal class PydanticSchemaFailure(val issues: List<PydanticSchemaIssue>) :
    RuntimeException("PYDANTIC_SCHEMA_INVALID")

/** 기존 non-strict Pydantic의 숫자·불리언 입력과 필드 별칭을 보존하는 공통 구조 파서다. 업무 교차 검증은 호출자가 수행한다. */
internal class PydanticSchema(
    private val nonAliasedTitles: Set<String> = emptySet(),
    private val defaultObjectProperties: Set<String> = emptySet(),
) {
    fun decode(value: JsonElement, schema: JsonObject): JsonObject {
        val issues = mutableListOf<PydanticSchemaIssue>()
        val result = parse(value, schema, schema, "", issues)
        if (issues.isNotEmpty()) throw PydanticSchemaFailure(issues)
        return result as? JsonObject ?: throw PydanticSchemaFailure(listOf(PydanticSchemaIssue("", "model_type")))
    }

    private fun parse(
        value: JsonElement, schema: JsonObject, root: JsonObject, path: String,
        issues: MutableList<PydanticSchemaIssue>,
    ): JsonElement {
        fun reject(code: String): JsonElement {
            issues += PydanticSchemaIssue(path, code); return value
        }
        schema["\$ref"]?.jsonPrimitive?.content?.let { reference ->
            return parse(
                value, root.getValue("\$defs").jsonObject.getValue(reference.substringAfterLast('/')).jsonObject, root,
                path, issues,
            )
        }
        schema["anyOf"]?.jsonArray?.let { alternatives ->
            val failures = mutableListOf<List<PydanticSchemaIssue>>()
            for (candidate in alternatives) {
                val trial = mutableListOf<PydanticSchemaIssue>()
                val decoded = parse(value, candidate.jsonObject, root, path, trial)
                if (trial.isEmpty()) return decoded
                failures += trial
            }
            // nullable의 null 분기 실패는 Pydantic의 필드 오류 위치에 중복하지 않는다.
            issues += failures.first()
            return value
        }
        schema["enum"]?.jsonArray?.let { if (value !in it) return reject("enum") }
        schema["const"]?.let { if (value != it) return reject("literal_error") }

        return when (schema["type"]?.jsonPrimitive?.content) {
            "object" -> {
                val source = value as? JsonObject ?: return reject(
                    if (schema["title"]?.jsonPrimitive?.content in nonAliasedTitles) "dict_type" else "model_type",
                )
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }.toSet()
                val output = linkedMapOf<String, JsonElement>()
                val consumed = mutableSetOf<String>()
                val aliases = schema["title"]?.jsonPrimitive?.content !in nonAliasedTitles

                // Pydantic의 선언 순서대로 검사하고 camel alias가 있으면 같은 snake 필드는 extra로 남긴다.
                properties.forEach { (name, definition) ->
                    val child = definition.jsonObject
                    val snake = Regex("(?<!^)([A-Z])").replace(name) { "_" + it.value.lowercase() }
                    val inputKey = when {
                        name in source -> name; aliases && snake in source -> snake; else -> null
                    }
                    val location = if (path.isEmpty()) inputKey ?: name else "$path.${inputKey ?: name}"
                    if (inputKey != null) {
                        consumed += inputKey
                        output[name] = parse(source.getValue(inputKey), child, root, location, issues)
                    } else if ("default" in child) output[name] = child.getValue("default")
                    else if (name in required) issues += PydanticSchemaIssue(location, "missing")
                    else if (child["type"] == JsonPrimitive("array")) output[name] = JsonArray(emptyList())
                    else if (name in defaultObjectProperties) output[name] =
                        parse(JsonObject(emptyMap()), child, root, location, issues)
                }
                source.filterKeys { it !in consumed }.forEach { (key, entry) ->
                    if (schema["additionalProperties"] == JsonPrimitive(false)) issues += PydanticSchemaIssue(
                        if (path.isEmpty()) key else "$path.$key", "extra_forbidden",
                    )
                    else if (properties.isEmpty()) output[key] = entry
                }
                JsonObject(output)
            }

            "array" -> {
                val array = value as? JsonArray ?: return reject("list_type")
                val items = schema["items"] as? JsonObject
                val output = JsonArray(
                    array.mapIndexed { index, entry ->
                        if (items == null) entry else parse(
                            entry, items, root, if (path.isEmpty()) "$index" else "$path.$index", issues,
                        )
                    },
                )
                if (array.size < (schema["minItems"]?.jsonPrimitive?.int ?: 0)) reject("too_short")
                if (array.size > (schema["maxItems"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)) reject("too_long")
                output
            }

            "string" -> {
                val primitive = value as? JsonPrimitive ?: return reject("string_type")
                if (!primitive.isString) return reject("string_type")
                val text = if ("enum" in schema || "const" in schema) primitive.content else primitive.content.trim()
                val size = text.codePointCount(0, text.length)
                if (size < (schema["minLength"]?.jsonPrimitive?.int ?: 0)) return reject("string_too_short")
                if (size > (schema["maxLength"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)) return reject("string_too_long")
                schema["pattern"]?.jsonPrimitive?.content?.let {
                    if (!Regex(it).containsMatchIn(text)) return reject(
                        "string_pattern_mismatch",
                    )
                }
                JsonPrimitive(text)
            }

            "integer", "number" -> {
                val integer = schema["type"] == JsonPrimitive("integer")
                val primitive = value as? JsonPrimitive ?: return reject(if (integer) "int_type" else "float_type")
                if (primitive == JsonNull) return reject(if (integer) "int_type" else "float_type")
                val numeric = when (primitive.takeUnless { it.isString }?.booleanOrNull) {
                    true -> BigDecimal.ONE; false -> BigDecimal.ZERO; else ->
                        try {
                            BigDecimal(primitive.content.trim().replace("_", ""))
                        } catch (_: NumberFormatException) {
                            null
                        }
                }
                    ?: return reject(if (integer) "int_parsing" else "float_parsing")
                if (integer && numeric.stripTrailingZeros().scale() > 0) return reject("int_from_float")
                schema["minimum"]?.jsonPrimitive?.content?.toBigDecimal()
                    ?.let { if (numeric < it) return reject("greater_than_equal") }
                schema["maximum"]?.jsonPrimitive?.content?.toBigDecimal()
                    ?.let { if (numeric > it) return reject("less_than_equal") }
                schema["exclusiveMinimum"]?.jsonPrimitive?.content?.toBigDecimal()
                    ?.let { if (numeric <= it) return reject("greater_than") }
                schema["exclusiveMaximum"]?.jsonPrimitive?.content?.toBigDecimal()
                    ?.let { if (numeric >= it) return reject("less_than") }
                if (integer) JsonPrimitive(numeric.toBigIntegerExact()) else JsonPrimitive(numeric.toDouble())
            }

            "boolean" -> {
                val primitive = value as? JsonPrimitive ?: return reject("bool_type")
                if (primitive == JsonNull) return reject("bool_type")
                if (primitive.isString && primitive.content in setOf("1.0", "0.0")) return reject("bool_parsing")
                when (primitive.content.lowercase()) {
                    "true", "1", "1.0", "on", "t", "yes", "y" -> JsonPrimitive(true)
                    "false", "0", "0.0", "off", "f", "no", "n" -> JsonPrimitive(false)
                    else -> reject("bool_parsing")
                }
            }

            "null" -> if (value == JsonNull) value else reject("none_required")
            else -> value
        }
    }
}
