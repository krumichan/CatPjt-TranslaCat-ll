package jp.co.translacat.languagelearning.features.leveltest.domain.policy

import java.text.Normalizer
import java.util.*
import java.util.regex.Pattern
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 기존 BE 내용 검증을 Kotlin 순수 함수로 보존한다. 저장소·프레임워크 의존성은 없다. */
internal class LevelContentContract {
    fun inspectValues(
        domain: LevelTestDomain?,
        itemType: LevelTestItemType?,
        instruction: String?,
        instructionLanguage: String?,
        learningLanguage: String?,
        answerMode: LevelTestAnswerMode?,
        answerLanguage: String?,
        promptText: String?,
        options: List<Option>?,
        answerKey: AnswerKey?,
        referencePayload: Map<String, Any?>,
        maxAnswerLength: Int?,
        maxAudioSeconds: Int?,
    ): Health {
        if (domain == null || itemType == null) return invalid("DOMAIN_OR_ITEM_TYPE_MISSING")
        if (instruction.isBlankValue()) return invalid("INSTRUCTION_MISSING")
        if (!isInstructionCompatible(instructionLanguage, instruction)) return invalid("INSTRUCTION_LANGUAGE_MISMATCH")
        if (promptText.isBlankValue()) return invalid("PROMPT_MISSING")
        val prompt = requireNotNull(promptText)
        if ("**" in prompt || !hasSafeInlineMarkup(prompt)) return invalid("UNSUPPORTED_PROMPT_MARKUP")

        inspectUnderlinePolicy(itemType, prompt, referencePayload).takeIf { !it.valid }?.let { return it }

        if (domain == LevelTestDomain.READING) {
            val readingPassage = referencePayload["readingPassage"].asString()
            val readingQuestion = referencePayload["readingQuestion"].asString()
            if (!hasUsableReadingStructure(prompt, readingPassage, readingQuestion)) {
                return invalid("READING_STRUCTURE_INVALID")
            }
            if (itemType == LevelTestItemType.READING_DISCOURSE_FUNCTION) {
                val emphasisText = referencePayload["emphasisText"].asString()
                if (emphasisText.isBlankValue() || countLiteral(
                        requireNotNull(readingPassage), emphasisText!!.trim(),
                    ) != 1
                ) {
                    return invalid("READING_EMPHASIS_INVALID")
                }
            }
        }

        inspectLearningLanguageLane(itemType, learningLanguage, referencePayload, options)
            .takeIf { !it.valid }
            ?.let { return it }

        if (itemType == LevelTestItemType.WRITING_TRANSLATION) {
            val source = referencePayload["translationSourceText"].asString()
            if (source.isBlankValue() || prompt.trim() != source!!.trim()) {
                return invalid("WRITING_TRANSLATION_SOURCE_INVALID")
            }
        }

        inspectGuidedTask(itemType, referencePayload).takeIf { !it.valid }?.let { return it }

        val expectedMode = expectedAnswerMode(itemType)
        if (answerMode != expectedMode) return invalid("ANSWER_MODE_MISMATCH")

        if (answerMode == LevelTestAnswerMode.CHOICE) {
            if (!answerLanguage.isBlankValue()) return invalid("CHOICE_ANSWER_LANGUAGE_PRESENT")
            inspectChoice(itemType, options, answerKey).takeIf { !it.valid }?.let { return it }
            if (itemType == LevelTestItemType.GRAMMAR_FORM_CHOICE) {
                inspectGrammarFormChoice(prompt, options.orEmpty(), requireNotNull(answerKey))
                    .takeIf { !it.valid }
                    ?.let { return it }
            }
        } else {
            if (answerLanguage.isBlankValue()) return invalid("ANSWER_LANGUAGE_MISSING")
            if (!options.isNullOrEmpty()) return invalid("NON_CHOICE_OPTIONS_PRESENT")
        }

        if (maxAnswerLength != null && maxAnswerLength !in 1..10_000) return invalid("MAX_ANSWER_LENGTH_INVALID")
        if (maxAudioSeconds != null && maxAudioSeconds !in 1..60) return invalid("MAX_AUDIO_SECONDS_INVALID")

        if (domain == LevelTestDomain.LISTENING) {
            val sourceText = referencePayload["sourceText"].asString()
            val listeningQuestion = referencePayload["listeningQuestion"].asString()
            if (sourceText.isBlankValue()) return invalid("LISTENING_SOURCE_TEXT_MISSING")
            if (listeningQuestion.isBlankValue()) return invalid("LISTENING_QUESTION_MISSING")
            if (prompt.trim() != listeningQuestion!!.trim()) return invalid("LISTENING_PROMPT_QUESTION_MISMATCH")
            if (hasListeningScriptLeak(prompt, sourceText!!)) return invalid("LISTENING_SCRIPT_LEAK")
            if (itemType == LevelTestItemType.LISTENING_INTERPRETATION) {
                val meanings = referencePayload["referenceMeanings"].listSize()
                val units = referencePayload["keyMeaningUnits"].listSize()
                if (meanings !in 2..3 || units !in 2..5) {
                    return invalid("LISTENING_INTERPRETATION_REFERENCE_INVALID")
                }
            }
        }

        if (itemType == LevelTestItemType.SPEAKING_REPEAT && referencePayload["referenceText"].asString()
                .isBlankValue()
        ) {
            return invalid("SPEAKING_REPEAT_REFERENCE_TEXT_MISSING")
        }
        return Health.ok()
    }

    private fun inspectChoice(
        itemType: LevelTestItemType,
        options: List<Option>?,
        answerKey: AnswerKey?,
    ): Health {
        if (answerKey == null) return invalid("ANSWER_KEY_MISSING")
        val safeOptions = options.orEmpty()
        if (safeOptions.any { it.key.isBlankValue() || it.text.isBlankValue() }) return invalid("CHOICE_OPTION_INVALID")
        val keys = safeOptions.map(Option::key)
        if (keys.toSet().size != keys.size) return invalid("CHOICE_OPTION_KEY_DUPLICATED")

        if (itemType == LevelTestItemType.GRAMMAR_SENTENCE_ORDER) {
            val order = answerKey.correctOrder
            if (keys.size < 2 || order == null || order.size != keys.size || keys.toSet() != order.toSet()) {
                return invalid("SENTENCE_ORDER_ANSWER_INVALID")
            }
            if (keys == order) return invalid("SENTENCE_ORDER_NOT_SHUFFLED")
            return Health.ok()
        }

        if (keys.size != 4 || answerKey.correctOptionKey.isBlankValue() || answerKey.correctOptionKey !in keys) {
            return invalid("CHOICE_ANSWER_INVALID")
        }
        val scores = answerKey.optionScores
        if (!scores.isNullOrEmpty()) {
            if (scores.keys != keys.toSet() || scores[answerKey.correctOptionKey] != 100 || scores.values.any { it !in 0..100 }) {
                return invalid("CHOICE_OPTION_SCORES_INVALID")
            }
            if (!answerKey.selectionPolicy.isBlankValue() && answerKey.selectionPolicy !in setOf(
                    "UNIQUE_ANSWER", "BEST_ANSWER",
                )
            ) {
                return invalid("CHOICE_SELECTION_POLICY_INVALID")
            }
        }
        return Health.ok()
    }

    private fun inspectUnderlinePolicy(
        itemType: LevelTestItemType,
        promptText: String,
        referencePayload: Map<String, Any?>,
    ): Health {
        if (countLiteral(promptText, "<u>") > 0 || countLiteral(promptText, "</u>") > 0) {
            return invalid("UNDERLINE_MARKUP_NOT_ALLOWED")
        }
        if (itemType != LevelTestItemType.VOCAB_PARAPHRASE_CHOICE) return Health.ok()

        val emphasisText = referencePayload["emphasisText"].asString()
        if (emphasisText.isBlankValue()) return invalid("VOCAB_PARAPHRASE_EMPHASIS_MISSING")
        val target = emphasisText!!.trim()
        if (target.codePointLength() > 80 || countLiteral(promptText, target) != 1) {
            return invalid("VOCAB_PARAPHRASE_EMPHASIS_INVALID")
        }
        return Health.ok()
    }

    private fun inspectLearningLanguageLane(
        itemType: LevelTestItemType,
        learningLanguage: String?,
        referencePayload: Map<String, Any?>,
        options: List<Option>?,
    ): Health {
        if (learningLanguage.isBlankValue()) return Health.ok()

        val learnerText = buildString {
            when {
                itemType.name.startsWith("READING_") -> {
                    appendLaneText(referencePayload["readingPassage"].asString())
                    appendLaneText(referencePayload["readingQuestion"].asString())
                }

                itemType == LevelTestItemType.LISTENING_GIST_CHOICE || itemType == LevelTestItemType.LISTENING_DETAIL_CHOICE -> {
                    appendLaneText(referencePayload["listeningQuestion"].asString())
                }

                else -> return Health.ok()
            }
            options.orEmpty().forEach { appendLaneText(it.text) }
        }
        if (learnerText.isBlank()) return invalid("LEARNER_TEXT_LANGUAGE_MISMATCH")

        val hasHangul = HANGUL.matcher(learnerText).find()
        val hasKana = KANA.matcher(learnerText).find()
        val hasAscii = ASCII_LETTER.matcher(learnerText).find()
        val mismatch = when (learningLanguage!!.trim().lowercase(Locale.ROOT)) {
            "ja" -> hasHangul || !hasKana
            "ko" -> hasKana || !hasHangul
            "en" -> hasHangul || hasKana || !hasAscii
            else -> false
        }
        return if (mismatch) invalid("LEARNER_TEXT_LANGUAGE_MISMATCH") else Health.ok()
    }

    private fun StringBuilder.appendLaneText(value: String?) {
        if (value.isBlankValue()) return
        if (isNotEmpty()) append('\n')
        append(value!!.trim())
    }

    private fun inspectGrammarFormChoice(
        promptText: String,
        options: List<Option>,
        answerKey: AnswerKey,
    ): Health {
        val matcher = GRAMMAR_BLANK.matcher(promptText)
        if (!matcher.find()) return invalid("GRAMMAR_FORM_BLANK_MISSING")
        val start = matcher.start()
        val end = matcher.end()
        if (matcher.find()) return invalid("GRAMMAR_FORM_MULTIPLE_BLANKS")
        val correctOption = options.firstOrNull { it.key == answerKey.correctOptionKey }?.text
        if (correctOption.isBlankValue()) return invalid("GRAMMAR_FORM_CORRECT_OPTION_MISSING")
        if (hasBoundaryDuplication(promptText, start, end, correctOption!!)) {
            return invalid("GRAMMAR_FORM_BOUNDARY_DUPLICATION")
        }
        return Health.ok()
    }

    private fun hasBoundaryDuplication(promptText: String, blankStart: Int, blankEnd: Int, option: String): Boolean {
        val left = promptText.substring(0, blankStart).trimEnd()
        val right = promptText.substring(blankEnd).trimStart()
        val answer = option.trim()
        if (answer.isBlank()) return true

        val rightMax = min(3, min(answer.length, right.length))
        for (size in rightMax downTo 1) {
            val overlap = answer.substring(answer.length - size)
            if (right.startsWith(overlap) && overlap.any(Char::isLetterOrDigit)) return true
        }
        val leftMax = min(3, min(answer.length, left.length))
        for (size in leftMax downTo 1) {
            val overlap = answer.substring(0, size)
            if (left.endsWith(overlap) && overlap.any(Char::isLetterOrDigit)) return true
        }
        return false
    }

    private fun countLiteral(value: String, token: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = value.indexOf(token, index)
            if (index < 0) return count
            count++
            index += token.length
        }
    }

    private fun expectedAnswerMode(itemType: LevelTestItemType): LevelTestAnswerMode = when (itemType) {
        LevelTestItemType.VOCAB_CONTEXT_CHOICE,
        LevelTestItemType.VOCAB_PARAPHRASE_CHOICE,
        LevelTestItemType.GRAMMAR_FORM_CHOICE,
        LevelTestItemType.GRAMMAR_SENTENCE_ORDER,
        LevelTestItemType.READING_GIST,
        LevelTestItemType.READING_DETAIL,
        LevelTestItemType.READING_DISCOURSE_FUNCTION,
        LevelTestItemType.READING_TEXT_INFERENCE,
        LevelTestItemType.LISTENING_GIST_CHOICE,
        LevelTestItemType.LISTENING_DETAIL_CHOICE,
            -> LevelTestAnswerMode.CHOICE

        LevelTestItemType.LISTENING_DICTATION,
        LevelTestItemType.LISTENING_INTERPRETATION,
        LevelTestItemType.WRITING_TRANSLATION,
        LevelTestItemType.WRITING_GUIDED_SENTENCE,
        LevelTestItemType.WRITING_SCENARIO_RESPONSE,
        LevelTestItemType.WRITING_SHORT_PARAGRAPH,
            -> LevelTestAnswerMode.TEXT

        LevelTestItemType.SPEAKING_REPEAT,
        LevelTestItemType.SPEAKING_GUIDED_RESPONSE,
        LevelTestItemType.SPEAKING_SHORT_RESPONSE,
            -> LevelTestAnswerMode.AUDIO
    }

    private fun inspectGuidedTask(itemType: LevelTestItemType, referencePayload: Map<String, Any?>): Health {
        val (minFacts, minIntents) = when (itemType) {
            LevelTestItemType.WRITING_GUIDED_SENTENCE,
            LevelTestItemType.SPEAKING_GUIDED_RESPONSE,
                -> 1 to 1

            LevelTestItemType.WRITING_SCENARIO_RESPONSE,
            LevelTestItemType.WRITING_SHORT_PARAGRAPH,
            LevelTestItemType.SPEAKING_SHORT_RESPONSE,
                -> 2 to 2

            else -> return Health.ok()
        }
        if (referencePayload["providedFacts"].listSize() < minFacts) return invalid("GUIDED_TASK_FACTS_INSUFFICIENT")
        if (referencePayload["requiredIntents"].listSize() < minIntents) return invalid(
            "GUIDED_TASK_INTENTS_INSUFFICIENT",
        )
        if (referencePayload["responseConstraints"].listSize() < 1) return invalid("GUIDED_TASK_CONSTRAINTS_MISSING")
        return Health.ok()
    }

    private fun hasUsableReadingStructure(
        promptText: String, readingPassage: String?, readingQuestion: String?,
    ): Boolean {
        if (readingPassage.isBlankValue() || readingQuestion.isBlankValue()) return false
        val passage = UNDERLINE_MARKER.matcher(readingPassage!!.trim()).replaceAll("")
        val question = UNDERLINE_MARKER.matcher(readingQuestion!!.trim()).replaceAll("")
        if (passage.codePointLength() < READING_MIN_PASSAGE_LENGTH || question.codePointLength() < READING_MIN_QUESTION_LENGTH) {
            return false
        }
        val canonical = "$passage\n\n$question"
        val normalizedPrompt =
            UNDERLINE_MARKER.matcher(promptText.replace("\r\n", "\n").replace('\r', '\n').trim()).replaceAll("")
        if (canonical != normalizedPrompt) return false
        return passage.any { it in ".!?。！？" }
    }

    private fun hasListeningScriptLeak(promptText: String, sourceText: String): Boolean {
        val prompt = compactForLeakComparison(promptText)
        val source = compactForLeakComparison(sourceText)
        if (prompt.isEmpty() || source.isEmpty()) return false
        if (prompt == source) return true
        if (source.length >= 12 && source in prompt) return true

        val threshold = if (source.length < 20) {
            max(8, source.length - 2)
        } else {
            max(20, ceil(source.length * 0.60).toInt())
        }
        if (prompt.length < threshold) return false
        return longestCommonSubstringLength(source, prompt) >= threshold
    }

    private fun compactForLeakComparison(value: String?): String {
        if (value == null) return ""
        val normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val result = StringBuilder(normalized.length)
        normalized.codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint)
        return result.toString()
    }

    private fun longestCommonSubstringLength(left: String, right: String): Int {
        var previous = IntArray(right.length + 1)
        var longest = 0
        for (leftIndex in 1..left.length) {
            val current = IntArray(right.length + 1)
            val leftChar = left[leftIndex - 1]
            for (rightIndex in 1..right.length) {
                if (leftChar == right[rightIndex - 1]) {
                    current[rightIndex] = previous[rightIndex - 1] + 1
                    longest = max(longest, current[rightIndex])
                }
            }
            previous = current
        }
        return longest
    }

    private fun hasSafeInlineMarkup(value: String): Boolean = !ANY_TAG.matcher(value).find()

    private fun isInstructionCompatible(languageCode: String?, instruction: String?): Boolean {
        if (instruction.isBlankValue()) return false
        return when (languageCode?.trim()?.lowercase(Locale.ROOT).orEmpty()) {
            "ko" -> HANGUL.matcher(instruction!!).find() && !KANA.matcher(instruction).find()
            "ja" -> KANA.matcher(instruction!!).find() && !HANGUL.matcher(instruction).find()
            "en" -> ASCII_LETTER.matcher(instruction!!).find() && !HANGUL.matcher(instruction).find() && !KANA.matcher(
                instruction,
            ).find()

            else -> true
        }
    }

    private fun Any?.listSize(): Int = (this as? List<*>)?.size ?: 0
    private fun Any?.asString(): String? = this?.toString()
    private fun String?.isBlankValue(): Boolean = this == null || isBlank()
    private fun String.codePointLength(): Int = codePointCount(0, length)
    private fun invalid(reason: String): Health = Health(false, reason)

    internal data class Health(val valid: Boolean, val reason: String?) {
        companion object {
            fun ok(): Health = Health(true, null)
        }
    }

    internal data class Option(val key: String, val text: String)

    internal data class AnswerKey(
        val correctOptionKey: String?,
        val correctOrder: List<String>?,
        val selectionPolicy: String?,
        val optionScores: Map<String, Int>?,
    )

    internal enum class LevelTestDomain {
        VOCABULARY,
        GRAMMAR,
        READING,
        LISTENING,
        WRITING,
        SPEAKING,
    }

    internal enum class LevelTestAnswerMode {
        CHOICE,
        TEXT,
        AUDIO,
    }

    internal enum class LevelTestItemType {
        VOCAB_CONTEXT_CHOICE,
        VOCAB_PARAPHRASE_CHOICE,
        GRAMMAR_FORM_CHOICE,
        GRAMMAR_SENTENCE_ORDER,
        READING_GIST,
        READING_DETAIL,
        READING_DISCOURSE_FUNCTION,
        READING_TEXT_INFERENCE,
        LISTENING_GIST_CHOICE,
        LISTENING_DETAIL_CHOICE,
        LISTENING_DICTATION,
        LISTENING_INTERPRETATION,
        WRITING_TRANSLATION,
        WRITING_GUIDED_SENTENCE,
        WRITING_SCENARIO_RESPONSE,
        WRITING_SHORT_PARAGRAPH,
        SPEAKING_REPEAT,
        SPEAKING_GUIDED_RESPONSE,
        SPEAKING_SHORT_RESPONSE,
    }

    private companion object {
        const val READING_MIN_PASSAGE_LENGTH = 24
        const val READING_MIN_QUESTION_LENGTH = 6
        val UNDERLINE_MARKER: Pattern = Pattern.compile("(?i)</?u>")
        val ANY_TAG: Pattern = Pattern.compile("<[^>]+>")
        val HANGUL: Pattern = Pattern.compile("[\\uAC00-\\uD7A3]")
        val KANA: Pattern = Pattern.compile("[\\u3040-\\u30FF]")
        val ASCII_LETTER: Pattern = Pattern.compile("[A-Za-z]")
        val GRAMMAR_BLANK: Pattern = Pattern.compile("(?:_{2,}|＿{2,})")
    }
}
