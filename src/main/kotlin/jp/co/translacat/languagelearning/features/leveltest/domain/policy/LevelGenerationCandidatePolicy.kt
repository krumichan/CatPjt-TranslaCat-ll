package jp.co.translacat.languagelearning.features.leveltest.domain.policy

import jp.co.translacat.languagelearning.features.listening.domain.policy.ListeningText
import kotlinx.serialization.json.*
import java.text.Normalizer
import java.util.*
import kotlin.math.ceil

/** 생성 후보의 기존 결정적 내용 검사다. 검증기·다양성·답변 평가 정책과 역할을 분리한다. */
internal object LevelGenerationCandidatePolicy {
    private class Rejection(val reason: String) : RuntimeException(reason)

    private val choices = setOf(
        "VOCAB_CONTEXT_CHOICE", "VOCAB_PARAPHRASE_CHOICE", "GRAMMAR_FORM_CHOICE", "GRAMMAR_SENTENCE_ORDER",
        "READING_GIST", "READING_DETAIL", "READING_DISCOURSE_FUNCTION", "READING_TEXT_INFERENCE",
        "LISTENING_GIST_CHOICE", "LISTENING_DETAIL_CHOICE",
    )
    private val blank = Regex("(?:_{2,}|＿{2,})")

    fun rejection(request: JsonObject, candidate: JsonObject): String? = try {
        validate(request, candidate)
        null
    } catch (failure: Rejection) {
        failure.reason
    }

    private fun validate(request: JsonObject, candidate: JsonObject) {
        val type = candidate.text("itemType")
        val mode = candidate.text("answerMode")
        val prompt = candidate.text("promptText")
        val ref = candidate.getValue("referencePayload").jsonObject
        val options = candidate.getValue("options").jsonArray.map { it.jsonObject }
        val answer = candidate.getValue("internalAnswerKey").jsonObject
        val learning = request.text("learningLanguage")
        val origin = request.text("originLanguage")

        // 먼저 요청한 출제 범위와 표시·답변 언어 계약을 기존 순서대로 확인한다.
        expect(
            candidate["domain"] == request["domain"] && candidate["itemType"] == request["itemType"],
            "Provider가 요청과 다른 Domain/ItemType을 반환했습니다.",
        )
        expect(
            candidate["complexityBand"] == request["targetComplexityBand"], "Provider가 요청과 다른 complexityBand를 반환했습니다.",
        )
        expect(candidate.text("instructionLanguage") == learning, "instructionLanguage는 learningLanguage여야 합니다.")
        val preferred = request.strings("preferredScenarioCategories").toSet()
        expect(
            preferred.isEmpty() || candidate.getValue("diversityMetadata").jsonObject.text(
                "scenarioCategory",
            ) in preferred,
            "scenarioCategory가 서버의 세션 균형 계획을 따르지 않았습니다. allowed=" + preferred.sorted().joinToString(","),
        )
        if (type in choices) {
            expect(mode == "CHOICE", "Choice Item의 answerMode가 CHOICE가 아닙니다.")
            expect(candidate["answerLanguage"] == JsonNull, "Choice Item에는 answerLanguage를 설정하지 않습니다.")
            if (type != "GRAMMAR_SENTENCE_ORDER") expect(
                options.map { it.text("key") }.toSet() == setOf("A", "B", "C", "D"),
                "4지선다 Choice Option key는 A/B/C/D여야 합니다.",
            )
        }
        if (type.startsWith("WRITING_")) expect(
            mode == "TEXT" && candidate.string("answerLanguage") == learning, "Writing Item의 답변 언어/방식이 유효하지 않습니다.",
        )
        if (type == "LISTENING_DICTATION") expect(
            mode == "TEXT" && candidate.string("answerLanguage") == learning,
            "Dictation은 learningLanguage TEXT 답변이어야 합니다.",
        )
        if (type == "LISTENING_INTERPRETATION") expect(
            mode == "TEXT" && candidate.string("answerLanguage") == origin,
            "Interpretation은 originLanguage TEXT 답변이어야 합니다.",
        )
        if (type.startsWith("SPEAKING_")) expect(
            mode == "AUDIO" && candidate.string("answerLanguage") == learning,
            "Speaking Item은 learningLanguage AUDIO 답변이어야 합니다.",
        )

        // 표시 마크업·정답 노출·정답 배열과 문항별 참조 자료의 결정적 조건을 검사한다.
        expect("**" !in prompt, "promptText에는 Markdown 굵은 글씨를 사용할 수 없습니다.")
        expect(!Regex("</?[A-Za-z][^>]*>").containsMatchIn(prompt), "promptText에는 HTML/XML 마크업을 사용할 수 없습니다.")
        if (type == "VOCAB_PARAPHRASE_CHOICE") {
            val emphasis = ref.string("emphasisText")
            expect(!emphasis.isNullOrBlank(), "VOCAB_PARAPHRASE_CHOICE에는 referencePayload.emphasisText가 필요합니다.")
            expect(
                emphasis!!.trim().points() <= 80 && occurrences(prompt, emphasis.trim()) == 1,
                "VOCAB_PARAPHRASE_CHOICE emphasisText는 promptText에 정확히 한 번 존재해야 합니다.",
            )
        }
        if (type == "VOCAB_CONTEXT_CHOICE") {
            val normalized = options.map { choiceText(it.text("text")) }
            expect(
                normalized.distinct().size == normalized.size, "VOCAB_CONTEXT_CHOICE Option에 실질적으로 동일한 표현이 중복되어 있습니다.",
            )
        }
        if (type in setOf("VOCAB_CONTEXT_CHOICE", "VOCAB_PARAPHRASE_CHOICE")) {
            val correct = options.firstOrNull { it["key"] == answer["correctOptionKey"] }?.text("text")
            if (!correct.isNullOrEmpty()) expect(
                choiceText(correct).isEmpty() || choiceText(correct) !in choiceText(prompt),
                "$type promptText에 정답 표현이 직접 노출되어 있습니다.",
            )
        }
        if (type == "GRAMMAR_FORM_CHOICE") {
            val matches = blank.findAll(prompt).toList()
            expect(matches.size == 1, "GRAMMAR_FORM_CHOICE에는 정확히 하나의 빈칸이 필요합니다.")
            val correct = options.firstOrNull { it["key"] == answer["correctOptionKey"] }?.text("text")
            expect(
                !correct.isNullOrEmpty() && !boundaryDuplication(prompt, matches.single(), correct),
                "GRAMMAR_FORM_CHOICE 정답을 빈칸에 삽입했을 때 활용 형태가 중복됩니다.",
            )
        }
        if (type == "GRAMMAR_SENTENCE_ORDER") expect(
            options.map { it.text("key") } != answer.strings("correctOrder"),
            "Sentence Order Option은 정답 순서 그대로 노출될 수 없습니다.",
        )
        if (type.startsWith("READING_")) reading(type, prompt, ref)
        if (type == "WRITING_TRANSLATION") {
            val source = ref.string("translationSourceText")
            expect(!source.isNullOrBlank(), "WRITING_TRANSLATION에는 translationSourceText가 필요합니다.")
            expect(
                source!!.trim() == prompt.trim(),
                "WRITING_TRANSLATION promptText는 translationSourceText와 정확히 일치해야 합니다.",
            )
        }
        if (type in LevelGenerationAssets.guided) guided(type, ref)
        if (type.startsWith("LISTENING_")) {
            val source = ref.string("sourceText")
            val question = ref.string("listeningQuestion")
            expect(!source.isNullOrBlank(), "Listening Item에는 referencePayload.sourceText가 필요합니다.")
            expect(!question.isNullOrBlank(), "Listening Item에는 referencePayload.listeningQuestion이 필요합니다.")
            expect(prompt.trim() == question!!.trim(), "Listening Item promptText는 listeningQuestion과 정확히 일치해야 합니다.")
            expect(!scriptLeak(prompt, source!!), "Listening Item promptText에 음성 sourceText가 노출되어 있습니다.")
        }

        // 화면에 드러나는 언어를 확인한 뒤 해석 기준 개수와 반복 발화의 기존 기억 부담 한도를 검사한다.
        languageLane(request, candidate)
        if (type == "LISTENING_INTERPRETATION") {
            expect(ref.strings("referenceMeanings").size in 2..3, "Interpretation referenceMeanings는 2~3개여야 합니다.")
            expect(ref.strings("keyMeaningUnits").size in 2..5, "Interpretation keyMeaningUnits는 2~5개여야 합니다.")
        }
        if (type == "SPEAKING_REPEAT") {
            val source = ref.string("referenceText")
            expect(!source.isNullOrBlank(), "SPEAKING_REPEAT에는 referenceText가 필요합니다.")
            if (request.getValue("questionNumber").jsonPrimitive.int in setOf(18, 19)) {
                val compact = Regex("(?U)[\\s\\x1c-\\x1f]+").replace(source!!.trim(), " ")
                expect(compact.points() <= 90, "SPEAKING_REPEAT referenceText가 지나치게 깁니다.")
                expect(
                    Regex("[.!?。！？]+").split(compact).count { it.isNotBlank() } <= 1,
                    "SPEAKING_REPEAT는 한 문장으로 구성해야 합니다.",
                )
                expect(
                    (candidate["maxAudioSeconds"]?.jsonPrimitive?.intOrNull ?: 0) <= 20,
                    "SPEAKING_REPEAT maxAudioSeconds는 20초 이하여야 합니다.",
                )
            }
        }
    }

    private fun reading(type: String, prompt: String, ref: JsonObject) {
        val passage = ref.string("readingPassage")?.trim()
        val question = ref.string("readingQuestion")?.trim()
        expect(!passage.isNullOrBlank(), "Reading Item에는 referencePayload.readingPassage가 필요합니다.")
        expect(!question.isNullOrBlank(), "Reading Item에는 referencePayload.readingQuestion이 필요합니다.")
        expect(
            prompt.trim() == "$passage\n\n$question", "Reading Item promptText는 passage/question의 canonical 조합이어야 합니다.",
        )
        expect(passage!!.points() >= 24 && question!!.points() >= 6, "Reading Item에는 학습자가 읽을 수 있는 충분한 지문과 질문이 필요합니다.")
        expect(passage.any { it in ".!?。！？" }, "Reading Item readingPassage에 완결된 문맥이 필요합니다.")
        if (type == "READING_DISCOURSE_FUNCTION") {
            val emphasis = ref.string("emphasisText")
            expect(!emphasis.isNullOrBlank(), "READING_DISCOURSE_FUNCTION에는 emphasisText가 필요합니다.")
            expect(
                occurrences(passage, emphasis!!.trim()) == 1,
                "READING_DISCOURSE_FUNCTION emphasisText는 readingPassage에 정확히 한 번 존재해야 합니다.",
            )
        }
    }

    private fun guided(type: String, ref: JsonObject) {
        val facts = ref.strings("providedFacts")
        val intents = ref.strings("requiredIntents")
        val constraints = ref.strings("responseConstraints")
        expect(facts.all { it.isNotBlank() }, "$type providedFacts가 유효하지 않습니다.")
        expect(intents.all { it.isNotBlank() }, "$type requiredIntents가 유효하지 않습니다.")
        expect(
            intents.none { Regex("[A-Z][A-Z0-9_]{2,}").matches(it.trim()) },
            "$type requiredIntents에 내부 enum token을 노출할 수 없습니다.",
        )
        expect(constraints.all { it.isNotBlank() }, "$type responseConstraints가 유효하지 않습니다.")
        val minimum = if (type in setOf(
                "WRITING_SCENARIO_RESPONSE", "WRITING_SHORT_PARAGRAPH", "SPEAKING_SHORT_RESPONSE",
            )
        ) 2 else 1
        expect(facts.size >= minimum, "${type}에는 최소 ${minimum}개의 제공 사실이 필요합니다.")
        expect(intents.size >= minimum, "${type}에는 최소 ${minimum}개의 필수 의사기능이 필요합니다.")
        expect(constraints.isNotEmpty(), "${type}에는 최소 1개의 응답 제약이 필요합니다.")
    }

    private fun languageLane(request: JsonObject, candidate: JsonObject) {
        val type = candidate.text("itemType")
        val ref = candidate.getValue("referencePayload").jsonObject
        val texts = mutableListOf(candidate.text("instruction"))
        if (type == "WRITING_TRANSLATION") {
            val source = ref.string("translationSourceText")
            expect(!source.isNullOrBlank(), "WRITING_TRANSLATION 번역 원문이 없습니다.")
            lane(
                request.text("originLanguage"), listOf(source!!),
                "WRITING_TRANSLATION sourceText 언어가 originLanguage와 일치하지 않습니다.",
            )
        } else texts += candidate.text("promptText")
        val referenceFields = when {
            type.startsWith("READING_") -> listOf("readingPassage", "readingQuestion")
            type.startsWith("LISTENING_") -> listOf("sourceText", "listeningQuestion"); else -> emptyList()
        }
        texts += referenceFields.mapNotNull { ref.string(it) }
        if (type.startsWith("READING_") || type.startsWith("LISTENING_") || type in choices)
            texts += candidate.getValue("options").jsonArray.map { it.jsonObject.text("text") }
        if (type in LevelGenerationAssets.guided) texts += listOf(
            "providedFacts", "requiredIntents", "responseConstraints",
        ).flatMap { ref.strings(it) }
        lane(
            request.text("learningLanguage"), texts,
            "$type 학습자 표시 텍스트가 learningLanguage=${request.text("learningLanguage")}와 일치하지 않습니다.",
        )
        if (type == "LISTENING_INTERPRETATION") {
            val origins = ref.strings("referenceMeanings") + ref.strings("keyMeaningUnits")
            if (origins.isNotEmpty()) lane(
                request.text("originLanguage"), origins,
                "LISTENING_INTERPRETATION 평가 기준 언어가 originLanguage와 일치하지 않습니다.",
            )
        }
    }

    private fun lane(language: String, values: List<String>, reason: String) {
        val text = values.filter { it.isNotBlank() }.joinToString("\n")
        expect(text.isNotEmpty(), reason)
        val hangul = Regex("[가-힣]").containsMatchIn(text)
        val kana = Regex("[\\u3040-\\u30ff]").containsMatchIn(text)
        val ascii = Regex("[A-Za-z]").containsMatchIn(text)
        val valid = when (language.trim().lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')) {
            "ko" -> !kana && hangul; "ja" -> !hangul && kana; "en" -> !hangul && !kana && ascii; else -> true
        }
        expect(valid, reason)
    }

    private fun boundaryDuplication(prompt: String, match: MatchResult, answer: String): Boolean {
        val left = prompt.substring(0, match.range.first).trimEnd().codePoints().toArray()
        val right = prompt.substring(match.range.last + 1).trimStart().codePoints().toArray()
        val text = answer.trim().codePoints().toArray()
        if (text.isEmpty()) return true
        for (size in minOf(3, text.size, right.size) downTo 1) {
            val overlap = text.takeLast(size)
            if (overlap == right.take(size) && overlap.any(::alnum)) return true
        }
        for (size in minOf(3, text.size, left.size) downTo 1) {
            val overlap = text.take(size)
            if (overlap == left.takeLast(size) && overlap.any(::alnum)) return true
        }
        return false
    }

    internal fun scriptLeak(prompt: String, source: String): Boolean {
        fun compact(text: String) = ListeningText.casefold(Normalizer.normalize(text, Normalizer.Form.NFKC))
            .codePoints()
            .filter(::alnum)
            .toArray()

        val right = compact(prompt)
        val left = compact(source)
        if (right.isEmpty() || left.isEmpty()) return false
        if (left.contentEquals(right)) return true
        val threshold = if (left.size < 20) maxOf(8, left.size - 2) else maxOf(20, ceil(left.size * 0.60).toInt())
        if (left.size >= 12 && contains(right, left)) return true
        if (right.size < threshold) return false

        // SequenceMatcher(autojunk=False)의 최장 연속 일치 길이만 필요하므로 같은 기준을 선형 메모리로 계산한다.
        var previous = IntArray(right.size + 1)
        for (code in left) {
            val current = IntArray(right.size + 1)
            for (index in right.indices) if (code == right[index]) {
                current[index + 1] = previous[index] + 1
                if (current[index + 1] >= threshold) return true
            }
            previous = current
        }
        return false
    }

    internal fun choiceText(value: String) = Regex("(?U)[\\s\\x1c-\\x1f]+").replace(
        ListeningText.casefold(Normalizer.normalize(value, Normalizer.Form.NFKC)), "",
    )

    private fun contains(value: IntArray, needle: IntArray) =
        value.size >= needle.size && (0..value.size - needle.size).any { start -> needle.indices.all { value[start + it] == needle[it] } }

    private fun alnum(code: Int) = Character.isLetterOrDigit(code) || Character.getType(code) in setOf(
        Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
    )

    private fun expect(value: Boolean, reason: String) {
        if (!value) throw Rejection(reason)
    }

    private fun JsonObject.text(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.strings(key: String) = (get(key) as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
    private fun String.points() = codePointCount(0, length)
    private fun occurrences(text: String, value: String) = Regex(Regex.escape(value)).findAll(text).count()
}
