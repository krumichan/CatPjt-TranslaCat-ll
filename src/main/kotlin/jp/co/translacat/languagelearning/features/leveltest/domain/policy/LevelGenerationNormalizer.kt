package jp.co.translacat.languagelearning.features.leveltest.domain.policy

import kotlinx.serialization.json.*
import java.util.*

internal data class LevelGenerationNormalization(val output: JsonObject, val stats: Map<String, Int>)

/** 기존 Python 생성 정규화의 순서를 보존한다. 누락된 의미 내용을 만들거나 검증 조건을 완화하지 않는다. */
internal object LevelGenerationNormalizer {
    private val whitespace = Regex("(?U)[\\s\\x1c-\\x1f]+")
    private val html = Regex("</?[A-Za-z][^>]*>")
    private val underline = Regex("<u>([\\s\\S]*?)</u>", RegexOption.IGNORE_CASE)
    private val underlineMarker = Regex("(<u>|</u>)", RegexOption.IGNORE_CASE)
    private val bold = Regex("\\*\\*([^*\\n][\\s\\S]*?)\\*\\*")
    private val templates by lazy {
        Json.parseToJsonElement(
            checkNotNull(
                javaClass.getResource(
                    "/leveltest/normalizer-templates.json",
                ),
            ).readText(),
        ).jsonObject
    }
    private val statsKeys = listOf(
        "prompt_text_fallbacks", "task_archetype_compactions", "semantic_summary_compactions",
        "max_audio_seconds_adjustments", "max_answer_length_adjustments", "answer_language_adjustments",
        "prompt_markup_sanitizations", "instruction_language_repairs", "sentence_order_shuffles",
        "option_key_canonicalizations", "listening_source_alias_repairs", "reference_payload_shape_repairs",
        "reading_structure_repairs", "listening_structure_repairs", "vocab_emphasis_repairs",
        "generation_plan_id_repairs",
        "enum_token_repairs", "writing_translation_repairs",
    )
    private val referenceAliases = linkedMapOf(
        "source_text" to "sourceText", "reference_meanings" to "referenceMeanings",
        "key_meaning_units" to "keyMeaningUnits", "reference_text" to "referenceText",
        "translation_source_text" to "translationSourceText",
        "emphasis_text" to "emphasisText", "reading_passage" to "readingPassage",
        "reading_question" to "readingQuestion",
        "listening_question" to "listeningQuestion", "provided_facts" to "providedFacts",
        "required_intents" to "requiredIntents",
        "response_constraints" to "responseConstraints",
    )
    private val referenceLists =
        setOf("referenceMeanings", "keyMeaningUnits", "providedFacts", "requiredIntents", "responseConstraints")
    private val choiceTypes = setOf(
        "VOCAB_CONTEXT_CHOICE", "VOCAB_PARAPHRASE_CHOICE", "GRAMMAR_FORM_CHOICE", "GRAMMAR_SENTENCE_ORDER",
        "READING_GIST", "READING_DETAIL", "READING_DISCOURSE_FUNCTION", "READING_TEXT_INFERENCE",
        "LISTENING_GIST_CHOICE", "LISTENING_DETAIL_CHOICE",
    )
    private val scenarios = setOf(
        "DAILY_LIFE", "WORK", "TRAVEL", "SHOPPING", "FOOD", "SERVICE", "LEARNING", "HOBBY",
        "DIGITAL_LIFE", "SOCIAL", "SCHEDULE", "HEALTH_GENERAL",
    )

    fun normalize(data: JsonObject, originLanguage: String?, learningLanguage: String?): LevelGenerationNormalization {
        val counts = statsKeys.associateWith { 0 }.toMutableMap()
        val candidates = data["candidates"] as? JsonArray ?: return LevelGenerationNormalization(data, counts)
        val output = JsonObject(
            data + ("candidates" to JsonArray(
                candidates.map { element ->
                    if (element !is JsonObject) element else State(counts, originLanguage, learningLanguage).candidate(
                        element,
                    )
                },
            )),
        )
        return LevelGenerationNormalization(output, counts)
    }

    private class State(val counts: MutableMap<String, Int>, val origin: String?, val learning: String?) {
        fun candidate(value: JsonObject): JsonObject {
            val item = value.toMutableMap()

            // 원본 순서: 설계 ID·표시 강조·안내를 먼저 정규화하고 기존 prompt에서 HTML만 제거한다.
            plan(item)
            vocabEmphasis(item)
            instruction(item)
            val promptKey = item.key("promptText", "prompt_text") ?: "promptText"
            item[promptKey].text()?.takeIf { it.isNotBlank() }?.let { original ->
                val cleaned = html.replace(original, "")
                item[promptKey] = JsonPrimitive(cleaned)
                if (cleaned != original) count("prompt_markup_sanitizations")
            }

            // 참조 원문과 표시 질문은 서로의 용도를 바꾸지 않으며 기존에 존재한 문자열만 이동한다.
            listeningSource(item)
            referenceShape(item)
            intents(item)
            translation(item)
            listeningStructure(item)
            readingStructure(item)
            readingEmphasis(item)
            optionKeys(item)
            sentenceOrder(item)
            item.objectAt("diversityMetadata", "diversity_metadata") { diversity(it) }
            answerLanguage(item)
            limit(item, "maxAudioSeconds", "max_audio_seconds", "AUDIO", 60, "max_audio_seconds_adjustments")
            limit(item, "maxAnswerLength", "max_answer_length", "TEXT", 10000, "max_answer_length_adjustments")
            return JsonObject(item)
        }

        private fun count(key: String, amount: Int = 1) {
            counts[key] = counts.getValue(key) + amount
        }

        private fun plan(item: MutableMap<String, JsonElement>) {
            val key = item.key("generationPlanId", "generation_plan_id") ?: return
            val original = item[key]
            if (original == JsonNull) return
            val value = original.text()?.trim()?.uppercase(Locale.ROOT)
            val normalized = if (item.type() == "VOCAB_CONTEXT_CHOICE" && value in setOf("A", "B")) JsonPrimitive(
                value,
            ) else JsonNull
            if (original != normalized) {
                item[key] = normalized; count("generation_plan_id_repairs")
            }
        }

        private fun vocabEmphasis(item: MutableMap<String, JsonElement>) {
            if (item.type() != "VOCAB_PARAPHRASE_CHOICE") return
            val promptKey = item.key("promptText", "prompt_text") ?: "promptText"
            val prompt = item[promptKey].text()?.takeIf { it.isNotBlank() } ?: return
            val payloadKey = item.key("referencePayload", "reference_payload") ?: "referencePayload"
            if (item[payloadKey] == null || item[payloadKey] == JsonNull) item[payloadKey] = JsonObject(emptyMap())
            val payload = (item[payloadKey] as? JsonObject)?.toMutableMap() ?: return
            val matches = underline.findAll(prompt).toList()
            if (!payload["emphasisText"].hasText() && matches.size == 1) {
                val target = matches.single().groupValues[1].trim()
                if (target.isNotEmpty() && target.points() <= 80) {
                    payload["emphasisText"] = JsonPrimitive(target)
                    count("vocab_emphasis_repairs")
                }
            }
            if (matches.isNotEmpty()) {
                val cleaned = underlineMarker.replace(underline.replace(prompt) { it.groupValues[1] }, "")
                if (cleaned != prompt) {
                    item[promptKey] = JsonPrimitive(cleaned); count("prompt_markup_sanitizations")
                }
            }
            item[payloadKey] = JsonObject(payload)
        }

        private fun instruction(item: MutableMap<String, JsonElement>) {
            val language = learning?.trim()?.takeIf { it.isNotEmpty() } ?: return
            val group = templates.getValue("groups").jsonObject[item.type()]?.text()
            val template = group?.let {
                (templates.getValue("instructions").jsonObject[primary(language)] as? JsonObject)?.get(it)
                    ?.text()
            }
            val key = item.key("instructionLanguage", "instruction_language") ?: "instructionLanguage"
            var changed = item[key] != JsonPrimitive(language)
            item[key] = JsonPrimitive(language)
            if (template != null && item["instruction"] != JsonPrimitive(template)) {
                item["instruction"] = JsonPrimitive(template)
                changed = true
            }
            if (changed) count("instruction_language_repairs")
        }

        private fun listeningSource(item: MutableMap<String, JsonElement>) {
            if (!item.type().startsWith("LISTENING_")) return
            val key = item.key("referencePayload", "reference_payload")
            val raw = key?.let { item[it] }
            val payload: MutableMap<String, JsonElement> = when (raw) {
                null, JsonNull -> mutableMapOf(); is JsonObject -> raw.toMutableMap(); else -> return
            }
            if (payload["sourceText"].hasText()) return
            val aliases = listOf(
                "source_text", "audioText", "audio_text", "transcript", "script", "referenceText", "reference_text",
                "listeningText", "listening_text",
            )
            val nested = aliases.firstOrNull { payload[it].hasText() }
            val top = if (nested != null) null else (listOf("sourceText") + aliases).firstOrNull { item[it].hasText() }
            val source = (nested?.let { payload[it] } ?: top?.let { item[it] }).text()?.trim() ?: return
            payload["sourceText"] = JsonPrimitive(source)
            if (nested != null) payload.remove(nested)
            if (top != null) item.remove(top)
            // 원본에 null 객체가 있던 경우의 처리도 그대로 두고 뒤의 shape 검사에서 판정한다.
            if (key == null || raw is JsonObject) item[key ?: "referencePayload"] = JsonObject(payload)
            count("listening_source_alias_repairs")
        }

        private fun referenceShape(item: MutableMap<String, JsonElement>) {
            val key = item.key("referencePayload", "reference_payload") ?: "referencePayload"
            var changed = item[key] == null || item[key] == JsonNull
            if (changed) item[key] = JsonObject(emptyMap())
            val payload = (item[key] as? JsonObject)?.toMutableMap() ?: return
            referenceAliases.forEach { (alias, canonical) ->
                if (canonical !in payload && alias in payload) {
                    payload[canonical] = payload.remove(alias)!!; changed = true
                }
            }
            referenceAliases.values.forEach { name ->
                if (name !in payload) {
                    payload[name] = if (name in referenceLists) JsonArray(emptyList()) else JsonNull; changed = true
                }
            }
            item[key] = JsonObject(payload)
            if (changed) count("reference_payload_shape_repairs")
        }

        private fun intents(item: MutableMap<String, JsonElement>) {
            val language = learning?.takeIf { it.isNotBlank() } ?: return
            val labels = templates.getValue("intents").jsonObject[primary(language)] as? JsonObject ?: return
            item.objectAt("referencePayload", "reference_payload") { payload ->
                val key = payload.key("requiredIntents", "required_intents") ?: return@objectAt
                val values = payload[key] as? JsonArray ?: return@objectAt
                var repaired = 0
                val normalized = values.map { entry ->
                    val label = entry.text()?.let { labels[token(it)] } ?: entry
                    if (label != entry) repaired++
                    label
                }
                if (repaired > 0) {
                    payload[key] = JsonArray(normalized); count("enum_token_repairs", repaired)
                }
            }
        }

        private fun translation(item: MutableMap<String, JsonElement>) {
            if (item.type() != "WRITING_TRANSLATION") return
            item.objectAt("referencePayload", "reference_payload") { payload ->
                val sourceKey =
                    payload.key("translationSourceText", "translation_source_text") ?: "translationSourceText"
                val promptKey = item.key("promptText", "prompt_text") ?: "promptText"
                val text = (item[promptKey].text()?.takeIf { it.isNotBlank() } ?: payload[sourceKey].text()
                    ?.takeIf { it.isNotBlank() })?.trim() ?: return@objectAt
                if (item[promptKey] != JsonPrimitive(text) || payload[sourceKey] != JsonPrimitive(text)) count(
                    "writing_translation_repairs",
                )
                item[promptKey] = JsonPrimitive(text)
                payload[sourceKey] = JsonPrimitive(text)
            }
        }

        private fun listeningStructure(item: MutableMap<String, JsonElement>) {
            if (!item.type().startsWith("LISTENING_")) return
            item.objectAt("referencePayload", "reference_payload") { payload ->
                val promptKey = item.key("promptText", "prompt_text") ?: "promptText"
                var changed = false
                if (!payload["listeningQuestion"].hasText() && item[promptKey].hasText()) {
                    payload["listeningQuestion"] = JsonPrimitive(item[promptKey].text()!!.trim())
                    changed = true
                }
                payload["listeningQuestion"].text()?.takeIf { it.isNotBlank() }?.let {
                    val text = JsonPrimitive(html.replace(it.trim(), "").trim())
                    if (payload["listeningQuestion"] != text || item[promptKey] != text) changed = true
                    payload["listeningQuestion"] = text
                    item[promptKey] = text
                }
                if (changed) count("listening_structure_repairs")
            }
        }

        private fun readingStructure(item: MutableMap<String, JsonElement>) {
            if (!item.type().startsWith("READING_")) return
            item.objectAt("referencePayload", "reference_payload") { payload ->
                val promptKey = item.key("promptText", "prompt_text") ?: "promptText"
                var changed = false
                if ((!payload["readingPassage"].hasText() || !payload["readingQuestion"].hasText()) && item[promptKey].hasText()) {
                    val prompt = item[promptKey].text()!!
                    val blocks = Regex("(?U)\\n\\s*\\n").split(prompt).map { it.trim() }.filter { it.isNotEmpty() }
                    val recovered = if (blocks.size >= 2) blocks.dropLast(1)
                        .joinToString("\n\n") to blocks.last() else splitReading(prompt)
                    listOf(
                        "readingPassage" to recovered.first, "readingQuestion" to recovered.second,
                    ).forEach { (key, value) ->
                        if (!payload[key].hasText() && !value.isNullOrBlank()) {
                            payload[key] = JsonPrimitive(value); changed = true
                        }
                    }
                }
                if (payload["readingPassage"].hasText() && payload["readingQuestion"].hasText()) {
                    var passage = payload["readingPassage"].text()!!.trim()
                    var question = payload["readingQuestion"].text()!!.trim()
                    if (item.type() == "READING_DISCOURSE_FUNCTION") {
                        val matches = bold.findAll(passage).toList()
                        if (!payload["emphasisText"].hasText() && matches.size == 1) {
                            payload["emphasisText"] = JsonPrimitive(matches.single().groupValues[1].trim()); changed =
                                true
                        }
                        val cleaned = bold.replace(passage) { it.groupValues[1] }
                        if (cleaned != passage) {
                            passage = cleaned; payload["readingPassage"] = JsonPrimitive(passage); count(
                                "prompt_markup_sanitizations",
                            ); changed = true
                        }
                    }
                    passage = html.replace(passage, "").trim()
                    question = html.replace(question, "").trim()
                    val prompt = "$passage\n\n$question"
                    if (payload["readingPassage"] != JsonPrimitive(
                            passage,
                        ) || payload["readingQuestion"] != JsonPrimitive(question) || item[promptKey] != JsonPrimitive(
                            prompt,
                        )
                    ) changed = true
                    payload["readingPassage"] = JsonPrimitive(passage)
                    payload["readingQuestion"] = JsonPrimitive(question)
                    item[promptKey] = JsonPrimitive(prompt)
                }
                if (changed) count("reading_structure_repairs")
            }
        }

        private fun readingEmphasis(item: MutableMap<String, JsonElement>) {
            if (item.type() != "READING_DISCOURSE_FUNCTION") return
            val promptKey = item.key("promptText", "prompt_text") ?: "promptText"
            val prompt = item[promptKey].text()?.takeIf { it.isNotBlank() } ?: return
            item.objectAt("referencePayload", "reference_payload") { payload ->
                val matches = bold.findAll(prompt).toList()
                if (!payload["emphasisText"].hasText() && matches.size == 1) {
                    payload["emphasisText"] = JsonPrimitive(matches.single().groupValues[1].trim())
                    count("reference_payload_shape_repairs")
                }
                val cleaned = bold.replace(prompt) { it.groupValues[1] }
                if (matches.isNotEmpty() && cleaned != prompt) {
                    item[promptKey] = JsonPrimitive(cleaned); count("prompt_markup_sanitizations")
                }
            }
        }

        private fun optionKeys(item: MutableMap<String, JsonElement>) {
            val values = item["options"] as? JsonArray ?: return
            if (values.isEmpty() || values.any { it !is JsonObject }) return
            val keys = values.map { it.jsonObject["key"].text()?.trim() ?: return }
            if (keys.any { it.isEmpty() } || keys.distinct().size != keys.size) return
            val valid = keys.all { Regex("^[A-Z][A-Z0-9_-]{0,15}$").matches(it) }
            val standard =
                item.type() == "GRAMMAR_SENTENCE_ORDER" || keys.size != 4 || keys.toSet() == setOf("A", "B", "C", "D")
            if (valid && standard) return
            val canonical = keys.indices.map { if (it < 26) ('A'.code + it).toChar().toString() else "A${it - 25}" }
            val mapping = keys.zip(canonical).toMap()
            var changed = 0
            item["options"] = JsonArray(
                values.mapIndexed { index, element ->
                    if (keys[index] == canonical[index]) element else {
                        changed++; JsonObject(element.jsonObject + ("key" to JsonPrimitive(canonical[index])))
                    }
                },
            )
            item.objectAt("internalAnswerKey", "internal_answer_key") { answer ->
                val key = answer.key("correctOptionKey", "correct_option_key")
                val mapped = key?.let { answer[it].text()?.trim() }?.let { mapping[it] }
                if (key != null && mapped != null) answer[key] = JsonPrimitive(mapped)
                mapKeys(answer, mapping, "correctOrder", "correct_order")
            }
            item.objectAt("choiceQualityAudit", "choice_quality_audit") { audit ->
                mapKeys(audit, mapping, "directlyCompatibleOptionKeys", "directly_compatible_option_keys")
            }
            count("option_key_canonicalizations", changed)
        }

        private fun sentenceOrder(item: MutableMap<String, JsonElement>) {
            if (item.type() != "GRAMMAR_SENTENCE_ORDER") return
            val options = item["options"] as? JsonArray ?: return
            if (options.size < 2) return
            val answer = item.value("internalAnswerKey", "internal_answer_key") as? JsonObject ?: return
            val order = answer.value("correctOrder", "correct_order") as? JsonArray ?: return
            if (options.map { (it as? JsonObject)?.get("key") ?: JsonNull } != order) return
            item["options"] = JsonArray(options.drop(1) + options.first())
            count("sentence_order_shuffles")
        }

        private fun diversity(data: MutableMap<String, JsonElement>) {
            listOf(
                Triple("scenarioCategory", "scenario_category", scenarios),
                Triple(
                    "communicativeIntent", "communicative_intent",
                    templates.getValue("intents").jsonObject.getValue("en").jsonObject.keys,
                ),
            ).forEach { (camel, snake, allowed) ->
                val key = data.key(camel, snake) ?: return@forEach
                val original = data[key].text() ?: return@forEach
                val canonical = token(original)
                if (canonical in allowed && canonical != original) {
                    data[key] = JsonPrimitive(canonical); count("enum_token_repairs")
                }
            }
            listOf(
                Triple("taskArchetype", "task_archetype", 100), Triple("semanticSummary", "semantic_summary", 500),
            ).forEach { (camel, snake, limit) ->
                val key = data.key(camel, snake) ?: return@forEach
                val original = data[key].text()?.takeIf { it.isNotBlank() } ?: return@forEach
                val normalized = compactLimit(original, limit)
                data[key] = JsonPrimitive(normalized)
                if (normalized != original) count(
                    if (limit == 100) "task_archetype_compactions" else "semantic_summary_compactions",
                )
            }
            listOf("contentHash" to "content_hash", "similarityKey" to "similarity_key").forEach { (camel, snake) ->
                val key = data.key(camel, snake) ?: return@forEach
                data[key].text()
                    ?.let { data[key] = it.trim().takeIf(String::isNotEmpty)?.let(::JsonPrimitive) ?: JsonNull }
            }
            listOf(
                "grammarFocusCodes" to "grammar_focus_codes", "lexicalFocusCodes" to "lexical_focus_codes",
            ).forEach { (camel, snake) ->
                val key = data.key(camel, snake) ?: return@forEach
                val values = data[key] as? JsonArray ?: return@forEach
                data[key] = JsonArray(
                    values.take(20)
                        .mapNotNull {
                            it.text()
                                ?.takeIf(String::isNotBlank)
                                ?.let { value -> JsonPrimitive(compact(value)) }
                        },
                )
            }
        }

        private fun answerLanguage(item: MutableMap<String, JsonElement>) {
            val type = item.type()
            val expected = when {
                type == "LISTENING_INTERPRETATION" -> origin
                type == "LISTENING_DICTATION" || type.startsWith("WRITING_") || type.startsWith("SPEAKING_") -> learning
                type in choiceTypes -> null
                else -> return
            }
            if (expected != null && expected.isBlank()) return
            val key = item.key("answerLanguage", "answer_language") ?: "answerLanguage"
            val value = expected?.let(::JsonPrimitive) ?: JsonNull
            if ((item[key] ?: JsonNull) != value) {
                item[key] = value; count("answer_language_adjustments")
            }
        }

        private fun limit(
            item: MutableMap<String, JsonElement>, camel: String, snake: String, mode: String, maximum: Int,
            statistic: String,
        ) {
            val key = item.key(camel, snake) ?: return
            val original = item.getValue(key)
            val numeric = (original as? JsonPrimitive)?.takeUnless { it.isString }?.doubleOrNull
            val normalized = if (item.value("answerMode", "answer_mode") != JsonPrimitive(mode)) JsonNull else
                if (numeric != null && numeric.isFinite() && numeric % 1.0 == 0.0) JsonPrimitive(
                    numeric.coerceIn(1.0, maximum.toDouble()).toInt(),
                ) else original
            val sameNumber = numeric != null && (normalized as? JsonPrimitive)?.doubleOrNull == numeric
            if (normalized != original && !sameNumber) {
                item[key] = normalized; count(statistic)
            }
        }
    }

    private fun mapKeys(data: MutableMap<String, JsonElement>, mapping: Map<String, String>, vararg names: String) {
        val key = data.key(*names) ?: return
        val values = data[key] as? JsonArray ?: return
        val mapped = values.map { it.text()?.trim()?.let(mapping::get) ?: return }
        data[key] = JsonArray(mapped.map(::JsonPrimitive))
    }

    private fun MutableMap<String, JsonElement>.objectAt(
        vararg keys: String, block: (MutableMap<String, JsonElement>) -> Unit,
    ) {
        val key = key(*keys) ?: return
        val nested = (get(key) as? JsonObject)?.toMutableMap() ?: return
        block(nested)
        put(key, JsonObject(nested))
    }

    private fun Map<String, JsonElement>.key(vararg names: String) = names.firstOrNull { containsKey(it) }
    private fun Map<String, JsonElement>.value(vararg names: String): JsonElement? = key(*names)?.let(::get)
    private fun Map<String, JsonElement>.type() = value("itemType", "item_type").text().orEmpty()
    private fun JsonElement?.text() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.hasText() = text()?.isNotBlank() == true
    private fun primary(language: String) =
        language.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')

    private fun token(value: String) = Regex("(?U)[\\s\\x1c-\\x1f-]+").replace(value.trim(), "_").uppercase(Locale.ROOT)
    private fun compact(value: String) = whitespace.replace(value, " ").trim()
    private fun String.points() = codePointCount(0, length)
    private fun String.takePoints(count: Int) = substring(0, offsetByCodePoints(0, minOf(count, points())))
    private fun compactLimit(value: String, max: Int): String {
        val compact = compact(value)
        if (compact.points() <= max) return compact
        val prefix = compact.takePoints(max + 1)
        val boundary = prefix.substringBeforeLast(' ', prefix).trimEnd()
        return if (boundary.points() >= max / 2) boundary else compact.takePoints(max).trimEnd()
    }

    private fun splitReading(value: String): Pair<String?, String?> {
        val text = value.trim()
        val boundaries = Regex("[。.!！]").findAll(text).map { it.range.last + 1 }.toList()
        for (boundary in (if (boundaries.lastOrNull() == text.length) boundaries.dropLast(
            1,
        ) else boundaries).asReversed()) {
            val passage = text.substring(0, boundary).trim()
            val question = text.substring(boundary).trim()
            if (passage.isEmpty() || question.points() < 6) continue
            if (listOf("?", "？", "か。", "か！").any(question::endsWith)) return passage to question
        }
        return null to null
    }
}
