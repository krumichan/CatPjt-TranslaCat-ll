package jp.co.translacat.languagelearning.features.leveltest.domain.policy

import jp.co.translacat.languagelearning.features.leveltest.domain.exception.LevelTestException
import jp.co.translacat.languagelearning.features.leveltest.domain.exception.levelInvalid
import jp.co.translacat.languagelearning.features.leveltest.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.*

internal object LevelTestRules {
    const val TOTAL = 20
    const val GENERATION_POLICY = "LEVEL_TEST_MULTI_SKILL"
    const val SCORING_POLICY = "LEVEL_TEST_SCORING"
    const val MODEL_CONFIG = "level-test-model-config"
    const val MAX_AUDIO_BYTES = 10 * 1024 * 1024
    const val AUDIO_RETENTION_DAYS = 7L
    const val MAX_MANUAL_RETRIES = 1

    data class Slot(val questionNumber: Int, val domain: LevelTestDomain, val itemType: LevelTestItemType)

    val recipe = listOf(
        "VOCAB_CONTEXT_CHOICE", "VOCAB_CONTEXT_CHOICE", "VOCAB_PARAPHRASE_CHOICE",
        "GRAMMAR_FORM_CHOICE", "GRAMMAR_FORM_CHOICE", "GRAMMAR_SENTENCE_ORDER",
        "READING_GIST", "READING_DETAIL", "READING_DISCOURSE_FUNCTION", "READING_TEXT_INFERENCE",
        "LISTENING_GIST_CHOICE", "LISTENING_DETAIL_CHOICE", "LISTENING_DICTATION", "LISTENING_INTERPRETATION",
        "WRITING_TRANSLATION", "WRITING_TRANSLATION", "WRITING_SHORT_PARAGRAPH",
        "SPEAKING_REPEAT", "SPEAKING_REPEAT", "SPEAKING_GUIDED_RESPONSE",
    ).mapIndexed { index, type ->
        val domain = when (index + 1) {
            in 1..3 -> LevelTestDomain.VOCABULARY
            in 4..6 -> LevelTestDomain.GRAMMAR
            in 7..10 -> LevelTestDomain.READING
            in 11..14 -> LevelTestDomain.LISTENING
            in 15..17 -> LevelTestDomain.WRITING
            else -> LevelTestDomain.SPEAKING
        }
        Slot(index + 1, domain, LevelTestItemType.valueOf(type))
    }

    fun slot(number: Int): Slot = recipe.getOrNull(number - 1) ?: levelInvalid("문항 번호가 범위를 벗어났습니다.")
    fun initialBand(score: Int?, recheck: Boolean): Int = if (!recheck || score == null) 2 else when {
        score < 40 -> 1; score < 55 -> 2; score < 70 -> 3; score < 85 -> 4; else -> 5
    }

    fun nextBand(current: Int, score: Int, choice: Boolean): Int =
        (current + if (choice) {
            if (score == 100) 1 else -1
        } else when {
            score >= 80 -> 1; score < 55 -> -1; else -> 0
        }).coerceIn(1, 5)

    fun candidateBands(current: Int, choice: Boolean): List<Int> =
        (if (choice) listOf(current - 1, current + 1) else listOf(current - 1, current, current + 1))
            .map { it.coerceIn(1, 5) }.distinct()

    fun band(score: Int): String = when {
        score < 40 -> "FOUNDATION"; score < 55 -> "BASIC"; score < 70 -> "INTERMEDIATE"
        score < 85 -> "UPPER_INTERMEDIATE"; else -> "ADVANCED"
    }

    fun domainScores(items: List<LevelItem>, evaluations: Map<Long, LevelEvaluationData>): Map<LevelTestDomain, Int> {
        if (items.size != TOTAL || items.map { it.questionNumber }.toSet() != (1..TOTAL).toSet()) {
            levelInvalid("레벨 테스트의 20문항이 모두 평가되어야 합니다.")
        }
        return items.groupBy { it.data.domain }.mapValues { (_, values) ->
            val total = values.fold(BigDecimal.ZERO) { sum, item ->
                val value = evaluations[item.id]
                if (value == null || !value.evaluable || value.score == null || value.score !in 0..100) {
                    levelInvalid("평가 가능한 모든 문항의 점수가 필요합니다.")
                }
                sum.add(BigDecimal.valueOf(value.score.toLong()))
            }
            total.divide(BigDecimal.valueOf(values.size.toLong()), 0, RoundingMode.HALF_UP).intValueExact()
        }.also { require(it.keys == LevelTestDomain.entries.toSet()) }
    }

    fun overall(scores: Map<LevelTestDomain, Int>): Int {
        require(scores.keys == LevelTestDomain.entries.toSet())
        val sum = LevelTestDomain.entries.fold(BigDecimal.ZERO) { result, domain ->
            val weight =
                if (domain == LevelTestDomain.VOCABULARY || domain == LevelTestDomain.GRAMMAR) "0.10" else "0.20"
            result.add(BigDecimal.valueOf(scores.getValue(domain).toLong()).multiply(BigDecimal(weight)))
        }
        return sum.setScale(0, RoundingMode.HALF_UP).intValueExact()
    }

    fun objective(question: LevelQuestionData, response: LevelSubmission): LevelEvaluationData {
        val key = question.internalAnswerKey
        val score = if (key.correctOrder.isNotEmpty()) {
            if (key.correctOrder == response.selectedOptionKeys) 100 else 0
        } else if (key.optionScores.isNotEmpty()) {
            if (key.optionScores.values.any { it !in 0..100 } || key.optionScores[key.correctOptionKey] != 100) {
                levelInvalid("객관식 부분점수 계약이 유효하지 않습니다.")
            }
            key.optionScores[response.selectedOptionKey] ?: levelInvalid("선택지를 확인해 주세요.")
        } else if (key.correctOptionKey == response.selectedOptionKey) 100 else 0
        return LevelEvaluationData(
            sessionId = question.sessionId, itemId = response.itemId,
            domain = question.domain, itemType = question.itemType, evaluable = true, score = score,
            confidence = 1.0, evaluationVersion = "LEVEL_TEST_OBJECTIVE",
        )
    }

    fun requireKey(value: String?): String = value?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
        ?: levelInvalid("200자 이하의 idempotencyKey가 필요합니다.")

    fun validateText(question: LevelQuestionData, option: String?, keys: List<String>, text: String?) {
        val valid = when (question.answerMode) {
            LevelTestAnswerMode.CHOICE -> if (question.itemType == LevelTestItemType.GRAMMAR_SENTENCE_ORDER) {
                option == null && text == null && keys.size == question.options.size && keys.toSet().size == keys.size &&
                    keys.toSet() == question.options.map { it.key }.toSet()
            } else option != null && keys.isEmpty() && text == null && question.options.any { it.key == option }

            LevelTestAnswerMode.TEXT -> !text.isNullOrBlank() && option == null && keys.isEmpty() &&
                text.length <= (question.maxAnswerLength ?: 10000)

            LevelTestAnswerMode.AUDIO -> false
        }
        if (!valid) throw LevelTestException("LEVEL_TEST_ANSWER_MODE_MISMATCH", 400, "문항의 답변 방식과 길이를 확인해 주세요.")
    }

    fun requiresAudio(type: LevelTestItemType): Boolean =
        type.name.startsWith("LISTENING_") || type == LevelTestItemType.SPEAKING_REPEAT

    fun zone(value: String): ZoneId = try {
        ZoneId.of(value)
    } catch (_: Exception) {
        ZoneId.of("Asia/Tokyo")
    }

    fun today(nowUtc: LocalDateTime, timezone: String): LocalDate =
        nowUtc.toInstant(ZoneOffset.UTC).atZone(zone(timezone)).toLocalDate()

    fun dayRange(nowUtc: LocalDateTime, timezone: String): Pair<LocalDateTime, LocalDateTime> {
        val zone = zone(timezone);
        val date = today(nowUtc, timezone)
        return LocalDateTime.ofInstant(date.atStartOfDay(zone).toInstant(), ZoneOffset.UTC) to
            LocalDateTime.ofInstant(date.plusDays(1).atStartOfDay(zone).toInstant(), ZoneOffset.UTC)
    }

    fun sha256(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    val rerecordReasons = setOf(
        "INVALID_AUDIO", "SILENCE_DETECTED", "UNSUPPORTED_AUDIO_FORMAT", "AUDIO_TOO_SHORT", "AUDIO_TOO_LONG",
        "LOW_STT_CONFIDENCE",
    )
    val categories = listOf(
        "DAILY_LIFE", "WORK", "TRAVEL", "SHOPPING", "FOOD", "SERVICE", "LEARNING", "HOBBY", "DIGITAL_LIFE", "SOCIAL",
        "SCHEDULE", "HEALTH_GENERAL",
    )

    fun scenarios(sessionId: Long, number: Int, current: List<String>, recent: List<String>): List<String> {
        fun counts(values: List<String>) =
            values.map { it.trim().uppercase(Locale.ROOT) }.filter { it in categories }.groupingBy { it }.eachCount()

        val currentCounts = counts(current);
        val recentCounts = counts(recent)
        val minimum = categories.minOf { currentCounts[it] ?: 0 }
        val rotation = Math.floorMod("$sessionId:$number:${slot(number).domain.name}".hashCode(), categories.size)
        return categories.filter { (currentCounts[it] ?: 0) == minimum }
            .sortedWith(
                compareBy(
                    { recentCounts[it] ?: 0 }, { Math.floorMod(categories.indexOf(it) - rotation, categories.size) },
                ),
            )
            .take(4)
    }

    /** 20문항 × 5 band 가상 슬롯의 원본 배분을 그대로 합산한다. */
    fun poolTargets(target: Int): Map<Pair<LevelTestItemType, Int>, Int> {
        require(target > 0)
        val result = linkedMapOf<Pair<LevelTestItemType, Int>, Int>()
        recipe.forEachIndexed { index, slot ->
            (1..5).forEach { band ->
                val allocation = target / 100 + if (index * 5 + band - 1 < target % 100) 1 else 0
                if (allocation > 0) result[slot.itemType to band] =
                    result.getOrDefault(slot.itemType to band, 0) + allocation
            }
        }
        return result
    }
}
