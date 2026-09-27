package jp.co.translacat.languagelearning.features.listening.domain.policy

import kotlinx.serialization.json.*

internal class ListeningProtocolFailure(val code: String = "INVALID_RESPONSE_SCHEMA") : RuntimeException(code)

internal object ListeningAssets {
    private val schemas = mutableMapOf<String, JsonObject>()
    private val prompts = mutableMapOf<String, String>()
    @Synchronized
    fun schema(name: String): JsonObject =
        schemas.getOrPut(name) { Json.parseToJsonElement(resource("$name-schema.json")).jsonObject }

    @Synchronized
    fun instructions(name: String): String = prompts.getOrPut(name) { resource("$name-system-prompt.txt") }

    // Python source loader의 universal newline과 맞춰 Windows 저장 시 생기는 CR만 제거한다.
    private fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/listening/$name"))
        .bufferedReader().use { it.readText() }.replace("\r\n", "\n")
}

/** 추출된 Pydantic Schema의 필드 제약을 LL에서 확인한 뒤 업무 교차 검증을 적용한다. */
internal object ListeningSchema {
    fun decode(value: JsonElement, schema: JsonObject, root: JsonObject = schema): JsonElement {
        // Pydantic의 기본값과 str_strip_whitespace를 적용한 뒤 같은 Schema로 검사한다.
        schema["\$ref"]?.jsonPrimitive?.content?.let { reference ->
            return decode(
                value, root.getValue("\$defs").jsonObject.getValue(reference.substringAfterLast('/')).jsonObject, root,
            )
        }
        schema["anyOf"]?.jsonArray?.let { alternatives ->
            for (candidate in alternatives) {
                try {
                    return decode(value, candidate.jsonObject, root)
                } catch (_: ListeningProtocolFailure) {
                }
            }
            invalid()
        }
        val normalized = when {
            value is JsonObject && schema["type"]?.jsonPrimitive?.content == "object" -> {
                val fields = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                val result = value.toMutableMap()
                fields.forEach { (key, field) ->
                    val child = field.jsonObject
                    if (key in value) result[key] = decode(value.getValue(key), child, root)
                    else if ("default" in child) result[key] = child.getValue("default")
                    else if (key !in required && child["type"] == JsonPrimitive("array")) result[key] =
                        JsonArray(emptyList())
                }
                JsonObject(result)
            }

            value is JsonArray && schema["items"] is JsonObject -> JsonArray(
                value.map { decode(it, schema.getValue("items").jsonObject, root) },
            )

            value is JsonPrimitive && value.isString && schema["type"] == JsonPrimitive(
                "string",
            ) && "enum" !in schema -> JsonPrimitive(value.content.trim())

            else -> value
        }
        validate(normalized, schema, root)
        return normalized
    }

    fun validate(value: JsonElement, schema: JsonObject, root: JsonObject = schema) {
        // 참조·분기 Schema는 동일 원본 definitions를 사용한다.
        schema["\$ref"]?.jsonPrimitive?.content?.let { reference ->
            if (!reference.startsWith("#/\$defs/")) invalid()
            validate(
                value, root.getValue("\$defs").jsonObject.getValue(reference.substringAfterLast('/')).jsonObject, root,
            )
            return
        }
        schema["anyOf"]?.jsonArray?.let { alternatives ->
            if (alternatives.none { candidate ->
                    try {
                        validate(value, candidate.jsonObject, root); true
                    } catch (_: ListeningProtocolFailure) {
                        false
                    }
                }) invalid()
            return
        }
        schema["enum"]?.jsonArray?.let { if (value !in it) invalid() }
        schema["const"]?.let { if (value != it) invalid() }

        // JSON 형식 및 숫자·문자열·배열 경계를 검사한다. 원문은 예외 메시지에 포함하지 않는다.
        when (schema["type"]?.jsonPrimitive?.content) {
            "object" -> {
                val objectValue = value as? JsonObject ?: invalid()
                val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
                val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
                if (!objectValue.keys.containsAll(required)) invalid()
                if (schema["additionalProperties"] == JsonPrimitive(
                        false,
                    ) && objectValue.keys.any { it !in properties }
                ) invalid()
                objectValue.forEach { (key, field) ->
                    (properties[key] as? JsonObject)?.let {
                        validate(
                            field, it, root,
                        )
                    }
                }
            }

            "array" -> {
                val array = value as? JsonArray ?: invalid()
                if (array.size < (schema["minItems"]?.jsonPrimitive?.int
                        ?: 0) || array.size > (schema["maxItems"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)
                ) invalid()
                (schema["items"] as? JsonObject)?.let { child -> array.forEach { validate(it, child, root) } }
            }

            "string" -> {
                val primitive = value as? JsonPrimitive ?: invalid()
                if (!primitive.isString) invalid()
                val content = primitive.content
                val length = content.codePointCount(0, content.length)
                if (length < (schema["minLength"]?.jsonPrimitive?.int
                        ?: 0) || length > (schema["maxLength"]?.jsonPrimitive?.int ?: Int.MAX_VALUE)
                ) invalid()
                schema["pattern"]?.jsonPrimitive?.content?.let { if (!Regex(it).containsMatchIn(content)) invalid() }
            }

            "integer", "number" -> {
                val primitive = value as? JsonPrimitive ?: invalid()
                val number = primitive.doubleOrNull ?: invalid()
                if (primitive.isString || !number.isFinite()) invalid()
                if (schema["type"]?.jsonPrimitive?.content == "integer" && number % 1.0 != 0.0) invalid()
                schema["minimum"]?.jsonPrimitive?.double?.let { if (number < it) invalid() }
                schema["maximum"]?.jsonPrimitive?.double?.let { if (number > it) invalid() }
                schema["exclusiveMinimum"]?.jsonPrimitive?.double?.let { if (number <= it) invalid() }
                schema["exclusiveMaximum"]?.jsonPrimitive?.double?.let { if (number >= it) invalid() }
            }

            "boolean" -> if ((value as? JsonPrimitive)?.booleanOrNull == null || value.jsonPrimitive.isString) invalid()
            "null" -> if (value != JsonNull) invalid()
        }
    }

    fun invalid(): Nothing = throw ListeningProtocolFailure()
}
