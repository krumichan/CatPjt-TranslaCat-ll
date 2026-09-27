package jp.co.translacat.languagelearning.features.practice.domain

import kotlinx.serialization.json.*
import java.text.Normalizer

internal object ReadingContentPolicy {
    private val json = Json { encodeDefaults = true }

    fun passage(request: ReadingRequest, firstOrder: Int, raw: JsonElement): ReadingPassage {
        val body = raw as? JsonObject ?: invalid()
        val id = body.string("passageId")
        val text = body.string("passageText").trim()
        if (id != (if (firstOrder == 1) "p1" else "p2") || text.isBlank()) invalid()
        language(request.learningLanguage, listOf(text))

        // 표면 길이를 새 난이도 기준으로 쓰지 않고 기존 문단 간 의존 조건만 유지한다.
        val demand =
            ReadingAssets.passageRecipe(request.mode, request.complexityBand).getValue("passageDemand").jsonObject
        if (demand.getValue("crossParagraphDependencyRequired").jsonPrimitive.boolean &&
            Regex("\\r?\\n\\s*\\r?\\n").split(text).count { it.isNotBlank() } < 2
        ) invalid()
        val plans = (body["questionPlans"] as? JsonArray)?.map { it as? JsonObject ?: invalid() } ?: invalid()
        val slots = ReadingPrompts.slots(request, firstOrder)
        if (plans.map { it.integer("globalOrder") } != slots.map { it.globalOrder }) invalid()
        val focuses = mutableSetOf<String>()
        plans.zip(slots).forEach { (plan, slot) ->
            val clue = plan.string("clueQuote")
            val focus = normalized(plan.string("questionFocus"))
            if (plan.string("skillTag") != slot.skillTag || plan.string("difficulty") != slot.difficulty.name ||
                plan.integer("complexityBand") != slot.complexityBand || clue.isBlank() || clue !in text ||
                focus.isBlank() || !focuses.add(focus)
            ) invalid()
            if (slot.skillTag in setOf("INFERENCE", "CONTEXT_INFERENCE")) {
                val inference = plan.string("unstatedInference").trim()
                if (inference.isEmpty() || normalized(inference) in normalized(text)) invalid()
                language(request.learningLanguage, listOf(inference))
            }
        }

        // B1와 문맥 추론은 기존의 숨은 추론 계획 coverage와 실제 단서 결합까지 검사한다.
        if (request.mode == "COMPREHENSION" && request.complexityBand == 1) {
            val clue = body.string("inferenceClueQuote").trim()
            val inference = body.string("unstatedInference").trim()
            if (clue.isEmpty() || inference.isEmpty() || clue !in text || normalized(inference) in normalized(
                    text,
                )
            ) invalid()
            language(request.learningLanguage, listOf(inference))
        }
        if (request.mode == "CONTEXT_INFERENCE") {
            val inferences = (body["inferencePlans"] as? JsonArray)?.map { it.jsonObject } ?: invalid()
            if (inferences.map { it.integer("questionOrder") } != slots.map { it.globalOrder }) invalid()
            val clues = mutableSetOf<String>()
            val conclusions = mutableSetOf<String>()
            inferences.forEach { item ->
                val clue = item.string("clueQuote").trim()
                val inference = item.string("unstatedInference").trim()
                if (clue.isEmpty() || inference.isEmpty() || clue !in text || !clues.add(normalized(clue)) ||
                    !conclusions.add(normalized(inference)) || normalized(inference) in normalized(text)
                ) invalid()
                language(request.learningLanguage, listOf(inference))
            }
        }
        return ReadingPassage(id, text, plans)
    }

    fun candidate(
        request: ReadingRequest, firstOrder: Int, slot: ReadingSlot, passage: ReadingPassage,
        raw: JsonObject, previous: List<PracticeQuestionContent>,
    ): PracticeQuestionContent {
        // 기존 Python처럼 서버 소유 curriculum label과 선택적 표현 후보만 정규화한다.
        val localOrder = slot.globalOrder - firstOrder + 1
        if (raw.integer("order") != localOrder) invalid()
        if (request.mode == "COMPREHENSION" && request.complexityBand == 1 && slot.skillTag == "INFERENCE") {
            val plan = passage.plans.single { it.integer("globalOrder") == slot.globalOrder }
            val clue = raw.string("inferenceClueQuote").trim()
            val inference = raw.string("unstatedInference").trim()
            if (clue.isBlank() || clue !in passage.text || inference.isBlank() || normalized(inference) in normalized(
                    passage.text,
                ) ||
                clue != plan.string("clueQuote").trim() || normalized(inference) != normalized(
                    plan.string("unstatedInference"),
                )
            ) invalid()
            language(request.learningLanguage, listOf(inference))
        }
        val fields = raw.filterKeys { it !in setOf("inferenceClueQuote", "unstatedInference") }.toMutableMap()
        fields["difficulty"] = JsonPrimitive(slot.difficulty.name)
        fields["complexityBand"] = JsonPrimitive(slot.complexityBand)
        fields["skillTag"] = JsonPrimitive(slot.skillTag)
        fields["reviewTarget"] = JsonPrimitive(false)
        fields["explanationOrigin"] = JsonPrimitive("PENDING")
        val candidates = (raw["vocabularyCandidates"] as? JsonArray).orEmpty().mapNotNull {
            (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content?.trim()
        }.filter { it.isNotEmpty() && it in passage.text }.distinct().take(3)
        fields["vocabularyCandidates"] = JsonArray(candidates.map(::JsonPrimitive))
        val question = try {
            json.decodeFromJsonElement<PracticeQuestionContent>(trimStrings(JsonObject(fields)))
        } catch (_: IllegalArgumentException) {
            invalid()
        }
        validateQuestion(request, question, passage, previous)
        return question
    }

    fun validateQuestion(
        request: ReadingRequest, question: PracticeQuestionContent, passage: ReadingPassage,
        previous: List<PracticeQuestionContent>,
    ) {
        val keys = question.options.map { it.key }
        // 원본 Pydantic의 문자열 길이·추가 필드 제한은 생성 schema와 별개로 파싱 뒤에도 유지한다.
        if (question.prompt.length !in 1..3000 || question.explanationOrigin.length !in 1..3000 ||
            question.explanationLearning.length !in 1..3000 || (question.evidenceText?.length ?: 0) > 3000 ||
            (question.passageId?.length ?: 0) > 80 || (question.passageText?.length ?: 0) > 12000 ||
            (question.targetExpression?.length ?: 0) > 300 || (question.canonicalKey?.length ?: 0) > 200 ||
            question.options.any { it.key.length !in 1..40 || it.text.length !in 1..1000 }
        ) invalid()
        if (question.questionType != PracticeQuestionType.SINGLE_CHOICE || keys.size != 4 || keys.distinct().size != 4 ||
            question.options.map { it.text.trim().lowercase() }.distinct().size != 4 ||
            question.correctAnswer.size != 1 || question.correctAnswer.single() !in keys ||
            question.passageId != passage.id || question.passageText != passage.text ||
            !question.targetExpression.isNullOrEmpty() || !question.canonicalKey.isNullOrEmpty()
        ) invalid()
        if (previous.any {
                it.passageId == question.passageId &&
                    normalized(Normalizer.normalize(it.prompt, Normalizer.Form.NFKC)) ==
                    normalized(Normalizer.normalize(question.prompt, Normalizer.Form.NFKC))
            }) {
            invalid("reading candidate repeats a normalized prompt on the same passage")
        }
        language(
            request.learningLanguage,
            listOfNotNull(
                question.passageText, question.prompt, question.targetExpression,
                question.evidenceText, question.explanationLearning,
            ) + question.options.map { it.text },
        )
    }

    fun repaired(question: PracticeQuestionContent, raw: JsonElement): PracticeQuestionContent {
        val items = (raw as? JsonObject)?.get("wrongOptions") as? JsonArray ?: invalid()
        val replacements = items.map { json.decodeFromJsonElement<PracticeOption>(it) }
        val answer = question.correctAnswer.single()
        val wrong = question.options.filter { it.key != answer }
        if (replacements.size != wrong.size || replacements.map { it.key }.toSet() != wrong.map { it.key }.toSet() ||
            replacements.map { it.key }.distinct().size != replacements.size
        ) invalid()
        val byKey = replacements.associate { it.key to it.text.trim() }
        val texts = byKey.values.map(::repairNormalized)
        if (texts.any(String::isBlank) || texts.distinct().size != texts.size ||
            repairNormalized(question.options.single { it.key == answer }.text) in texts
        ) invalid()
        return question.copy(
            options = question.options.map { if (it.key == answer) it else it.copy(text = byKey.getValue(it.key)) },
        )
    }

    fun explanation(language: String, text: String): String {
        val stripped = text.trim()
        val artifacts = listOf(
            "immutableTaskFact", "APPLICATION_SELECTED_IMMUTABLE", "evidenceLearning", "explanationLearning",
            "evidenceText", "correctAnswerText", "targetExpression", "```json", "tool_call", "function_call",
        )
        if (artifacts.any { stripped.contains(it, ignoreCase = true) } ||
            Regex(
                "^(?:json|tool|assistant|system|developer|analysis|metadata)\\s*:", RegexOption.IGNORE_CASE,
            ).containsMatchIn(stripped)) invalid()
        if (stripped.startsWith('{') || stripped.startsWith('[')) {
            val parsed = runCatching { Json.parseToJsonElement(stripped) }.getOrNull()
            if (parsed is JsonObject || parsed is JsonArray) invalid()
        }
        language(language, listOf(stripped), allowMixed = true)
        return stripped
    }

    fun language(code: String, values: List<String>, allowMixed: Boolean = false) {
        val text = values.filter(String::isNotBlank).joinToString("\n")
        if (text.isBlank()) invalid()
        val hangul = Regex("[가-힣]").containsMatchIn(text)
        val kana = Regex("[ぁ-ヿ]").containsMatchIn(text)
        val cjk = Regex("[㐀-䶿一-鿿]").containsMatchIn(text)
        val ascii = Regex("[A-Za-z]").containsMatchIn(text)
        val (mismatch, absent) = when (code.trim().lowercase().substringBefore('-').substringBefore('_')) {
            "ja" -> hangul to !(kana || cjk)
            "ko" -> kana to !hangul
            "en" -> (hangul || kana || cjk) to !ascii
            else -> false to false
        }
        if ((mismatch && !allowMixed) || absent) invalid()
    }

    fun normalized(value: String) = value.replace(Regex("[^0-9A-Za-zぁ-ヿ㐀-䶿一-鿿가-힣]+"), "").lowercase()
    private fun repairNormalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), " ").trim().lowercase()

    private fun JsonObject.string(key: String) =
        (get(key) as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: invalid()

    private fun JsonObject.integer(key: String) = (get(key) as? JsonPrimitive)?.intOrNull ?: invalid()
    private fun trimStrings(value: JsonElement): JsonElement = when (value) {
        is JsonObject -> JsonObject(value.mapValues { trimStrings(it.value) })
        is JsonArray -> JsonArray(value.map(::trimStrings))
        is JsonPrimitive -> if (value.isString) JsonPrimitive(value.content.trim()) else value
    }

    private fun invalid(reason: String? = null): Nothing = throw PracticeFailure("AI_SCHEMA_INVALID", 422, reason)
}
