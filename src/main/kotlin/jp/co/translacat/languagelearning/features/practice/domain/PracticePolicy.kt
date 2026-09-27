package jp.co.translacat.languagelearning.features.practice.domain

import java.text.Normalizer
import java.util.*

/** 현재 Core의 Reading-first, 첫 시도 채점, 세 번 재도전 정책을 보존한다. */
internal object PracticePolicy {
    val readingModes = listOf("COMPREHENSION", "STRUCTURE", "CONTEXT_INFERENCE")
    const val VOCABULARY_RETIRED = "DAILY_VOCABULARY_RETIRED"
    const val B5_STRUCTURE_DEFERRED = "READING_B5_STRUCTURE_DEFERRED"

    fun requireNewLearning(domain: PracticeDomain, mode: String, band: Int) {
        if (domain == PracticeDomain.VOCABULARY) throw PracticeFailure(VOCABULARY_RETIRED, 400)
        if (mode !in readingModes || band !in 1..5) throw PracticeFailure("LANGUAGE_LEARNING_SETTING_INVALID", 400)
        if (mode == "STRUCTURE" && slots(mode, band).any { it.complexityBand == 5 }) {
            throw PracticeFailure(B5_STRUCTURE_DEFERRED, 400)
        }
    }

    fun complexityBand(baseScore: Double?, recentScores: List<Double>): Int {
        val base = when {
            baseScore == null -> 3
            baseScore < 40 -> 1
            baseScore < 55 -> 2
            baseScore < 70 -> 3
            baseScore < 85 -> 4
            else -> 5
        }
        val average = recentScores.take(5).average()
        return (base + when {
            average >= 85 -> 1
            average < 55 -> -1
            else -> 0
        }).coerceIn(1, 5)
    }

    fun slots(mode: String, band: Int): List<ReadingSlot> {
        require(mode in readingModes && band in 1..5)
        val skills = when (mode) {
            "COMPREHENSION" -> listOf("CONTENT", "DETAIL", "INFERENCE", "DETAIL", "INFERENCE")
            "STRUCTURE" -> listOf("GIST", "STRUCTURE", "STRUCTURE", "GIST", "STRUCTURE")
            else -> listOf("CONTEXT_INFERENCE", "INFERENCE", "CONTEXT_INFERENCE", "INFERENCE", "CONTEXT_INFERENCE")
        }
        val mix = listOf(
            PracticeDifficulty.CURRENT, PracticeDifficulty.EASIER,
            PracticeDifficulty.CURRENT, PracticeDifficulty.CHALLENGE, PracticeDifficulty.CURRENT,
        )
        return mix.mapIndexed { index, difficulty ->
            val targetBand = when (difficulty) {
                PracticeDifficulty.EASIER -> (band - 1).coerceAtLeast(1)
                PracticeDifficulty.CURRENT -> band
                PracticeDifficulty.CHALLENGE -> (band + 1).coerceAtMost(5)
            }
            val skill = when {
                mode == "COMPREHENSION" && targetBand >= 4 && skills[index] == "DETAIL" -> "INFERENCE"
                mode == "STRUCTURE" && targetBand >= 3 -> "STRUCTURE"
                else -> skills[index]
            }
            ReadingSlot(index + 1, difficulty, targetBand, skill)
        }
    }

    fun validateAnswer(
        question: PracticeQuestionContent, answer: List<String>, attempts: List<PracticeAttempt>,
    ): List<String> {
        // 답변 모양을 먼저 검사하고, 이미 정답이거나 세 번 시도한 문항은 잠금 안에서 거부한다.
        val normalized = answer.map { it.trim() }
        if (normalized.isEmpty() || normalized.any(String::isBlank) || normalized.distinct().size != normalized.size) {
            throw PracticeFailure("LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED")
        }
        val keys = question.options.map { it.key }
        if (question.questionType == PracticeQuestionType.SINGLE_CHOICE &&
            (normalized.size != 1 || normalized.single() !in keys)
        ) {
            throw PracticeFailure("LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED")
        }
        if (question.questionType == PracticeQuestionType.ORDERING &&
            (normalized.size != question.correctAnswer.size || normalized.size != keys.size || normalized.toSet() != keys.toSet())
        ) {
            throw PracticeFailure("LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED")
        }
        if (attempts.lastOrNull()?.correct == true || attempts.size >= 3) throw PracticeFailure(
            "LANGUAGE_LEARNING_ANSWER_NOT_ALLOWED",
        )
        return normalized
    }

    fun firstMissing(questions: List<PracticeQuestion>, count: Int): Int =
        (1..count).firstOrNull { order -> questions.none { it.content.order == order } } ?: count + 1

    fun completedPassages(questions: List<PracticeQuestion>, answeredIds: Set<Long>): Set<String> =
        listOf("p1", "p2").filter { passage ->
            val range = if (passage == "p1") 1..3 else 4..5
            range.all { order ->
                val matches = questions.filter { it.content.order == order }
                matches.size == 1 && matches.single().content.passageId == passage && matches.single().id in answeredIds
            } && questions.filter { it.content.passageId == passage }
                .map { it.content.passageText }
                .distinct().size == 1
        }.toSet()

    fun candidates(content: PracticeQuestionContent): List<String> {
        val passage = content.passageText ?: return emptyList()
        return content.vocabularyCandidates.map(String::trim)
            .filter { it.isNotEmpty() && passage.contains(it) }
            .distinct()
            .take(3)
    }

    fun metrics(questions: List<PracticeQuestion>, attempts: List<PracticeAttempt>): List<PracticeMetric> {
        val first = attempts.filter { it.official }.associateBy { it.questionId }
        return questions.filter { it.id in first }.groupBy { it.content.skillTag }.map { (skill, grouped) ->
            PracticeMetric(
                skill, round(grouped.count { first.getValue(it.id).correct } * 100.0 / grouped.size),
                grouped.size,
            )
        }
    }

    fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
    fun round(value: Double) = Math.round(value * 100.0) / 100.0
}

internal data class ReadingSlot(
    val globalOrder: Int, val difficulty: PracticeDifficulty, val complexityBand: Int, val skillTag: String,
)
