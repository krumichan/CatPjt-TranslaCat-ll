package jp.co.translacat.languagelearning.features.practice.application

import jp.co.translacat.languagelearning.features.practice.domain.*
import jp.co.translacat.languagelearning.shared.identity.LearningPublicId
import kotlinx.serialization.json.*
import java.time.LocalDate

internal class PracticeReadService(private val work: PracticeUnitOfWork) {
    private val json = Json { encodeDefaults = true }

    suspend fun get(userId: Long, setId: Long): JsonObject = work.read {
        // 사용자 소유 세트와 실제 답변을 함께 읽어 공개 가능한 지문 범위를 계산한다.
        val set = records.find(userId, setId) ?: throw PracticeFailure("LANGUAGE_LEARNING_DAILY_SET_NOT_FOUND", 400)
        val questions = records.questions(setId)
        val attempts = records.attempts(setId)
        val completed = PracticePolicy.completedPassages(questions, attempts.map { it.questionId }.toSet())

        // 외부 DTO 이름과 첫 시도 집계를 유지하고, 문항별 정답 공개는 별도 매핑에서 제한한다.
        buildJsonObject {
            put("practiceSetId", LearningPublicId.encode(setId))
            put("learningDate", set.learningDate.toString())
            put("domain", set.domain.name)
            put("mode", set.mode)
            put("status", set.status.name)
            put("questionCount", set.questionCount)
            put("answeredCount", attempts.count { it.official })
            put("correctCount", attempts.count { it.official && it.correct })
            put("officialScore", set.officialScore?.let(::JsonPrimitive) ?: JsonNull)
            put("complexityBand", set.complexityBand)
            put("promptVersion", set.promptVersion?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "metrics",
                JsonArray(
                    records.metrics(setId).map { metric ->
                        buildJsonObject {
                            put("skillTag", metric.skillTag)
                            put("score", metric.score)
                            put("sampleCount", metric.sampleCount)
                        }
                    },
                ),
            )
            put(
                "questions",
                JsonArray(
                    questions.map { question ->
                        question(
                            question, attempts.filter { it.questionId == question.id },
                            question.content.passageId in completed,
                        )
                    },
                ),
            )
            put("generationStatus", set.generationStatus.name)
            put("generatedQuestionCount", questions.size)
            put("generationFailureMessage", set.failureCode?.let(::JsonPrimitive) ?: JsonNull)
        }
    }

    suspend fun statuses(userId: Long, date: LocalDate, domain: PracticeDomain): JsonArray = work.read {
        JsonArray(
            records.today(userId, date, domain)
                .filter { domain != PracticeDomain.VOCABULARY || it.mode == "CONTEXTUAL_CHOICE" }
                .map { set ->
                    buildJsonObject {
                        put("mode", set.mode)
                        put("practiceSetId", LearningPublicId.encode(set.id))
                        put("status", set.status.name)
                        put("answeredCount", records.attempts(set.id).count { it.official })
                        put("questionCount", set.questionCount)
                        put("officialScore", set.officialScore?.let(::JsonPrimitive) ?: JsonNull)
                        put("generationStatus", set.generationStatus.name)
                        put("generatedQuestionCount", records.questions(set.id).size)
                        put("generationFailureMessage", set.failureCode?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
        )
    }

    suspend fun mastery(userId: Long): JsonObject = work.read {
        // 미평가 표현은 단계 수에는 포함하되 기존 평균·취약 표현 계산에서는 제외한다.
        val values = records.masteries(userId)
        val evaluated = values.filter { it.evaluationCount > 0 }

        buildJsonObject {
            put("total", values.size)
            put(
                "averageScore",
                if (evaluated.isEmpty()) 0.0 else PracticePolicy.round(evaluated.map { it.score }.average()),
            )
            put("newCount", values.count { it.stage == "NEW" })
            put("learningCount", values.count { it.stage == "LEARNING" })
            put("familiarCount", values.count { it.stage == "FAMILIAR" })
            put("strongCount", values.count { it.stage == "STRONG" })
            put("masteredCount", values.count { it.stage == "MASTERED" })
            put(
                "weakest",
                JsonArray(
                    evaluated.take(20).map { value ->
                        buildJsonObject {
                            put("canonicalKey", value.canonicalKey)
                            put("displayExpression", value.displayExpression)
                            put("score", value.score)
                            put("stage", value.stage)
                            put("evaluationCount", value.evaluationCount)
                        }
                    },
                ),
            )
        }
    }

    suspend fun report(userId: Long, from: LocalDate, to: LocalDate): JsonObject = work.read {
        // 기존 홈·이력·추세 계산이 필요한 저장 사실만 전달하며 문항·사용자 답변은 포함하지 않는다.
        buildJsonObject {
            put(
                "sets",
                JsonArray(
                    records.range(userId, from, to).map { set ->
                        buildJsonObject {
                            put("setId", LearningPublicId.encode(set.id))
                            put("learningDate", set.learningDate.toString())
                            put("domain", set.domain.name)
                            put("mode", set.mode)
                            put("status", set.status.name)
                            put("questionCount", set.questionCount)
                            put("answeredCount", records.attempts(set.id).count { it.official })
                            put("officialScore", set.officialScore?.let(::JsonPrimitive) ?: JsonNull)
                            put("startedAt", set.startedAt.toString())
                            put("completedAt", set.completedAt?.toString()?.let(::JsonPrimitive) ?: JsonNull)
                            put(
                                "metrics",
                                JsonArray(
                                    records.metrics(set.id).map { metric ->
                                        buildJsonObject {
                                            put("skillTag", metric.skillTag)
                                            put("score", metric.score)
                                            put("sampleCount", metric.sampleCount)
                                        }
                                    },
                                ),
                            )
                        }
                    },
                ),
            )
        }
    }

    fun answer(result: PracticeAnswerResult): JsonObject = buildJsonObject {
        put("questionId", LearningPublicId.encode(result.questionId))
        put("attemptNo", result.attemptNo)
        put("correct", result.correct)
        put("official", result.official)
        put("setCompleted", result.setCompleted)
        put("officialScore", result.officialScore?.let(::JsonPrimitive) ?: JsonNull)
        put("correctAnswer", json.encodeToJsonElement(result.content.correctAnswer))
        put("evidenceText", result.content.evidenceText?.let(::JsonPrimitive) ?: JsonNull)
        put("explanationOrigin", result.content.explanationOrigin)
        put("explanationLearning", result.content.explanationLearning)
    }

    private fun question(
        question: PracticeQuestion, attempts: List<PracticeAttempt>, passageCompleted: Boolean,
    ): JsonObject {
        // 정답·해설은 첫 답변 뒤, 선택적 어휘 표현은 해당 지문의 모든 첫 답변 뒤에만 공개한다.
        val content = question.content
        val answered = attempts.isNotEmpty()
        val latest = attempts.lastOrNull()
        return buildJsonObject {
            put("questionId", LearningPublicId.encode(question.id))
            put("order", content.order)
            put("questionType", content.questionType.name)
            put("difficulty", content.difficulty.name)
            put("complexityBand", content.complexityBand)
            put("passageId", content.passageId?.let(::JsonPrimitive) ?: JsonNull)
            put("passageText", content.passageText?.let(::JsonPrimitive) ?: JsonNull)
            put("prompt", content.prompt)
            put("options", json.encodeToJsonElement(content.options))
            put("skillTag", content.skillTag)
            put("targetExpression", content.targetExpression?.let(::JsonPrimitive) ?: JsonNull)
            put("reviewTarget", content.reviewTarget)
            put(
                "vocabularyCandidates",
                json.encodeToJsonElement(if (passageCompleted) PracticePolicy.candidates(content) else emptyList()),
            )
            put("answered", answered)
            put("correct", latest?.correct == true)
            put("canRetry", latest != null && !latest.correct && attempts.size < 3)
            put(
                "attempts",
                JsonArray(
                    attempts.map { attempt ->
                        buildJsonObject {
                            put("attemptId", LearningPublicId.encode(attempt.id))
                            put("attemptNo", attempt.attemptNo)
                            put("answer", json.encodeToJsonElement(attempt.answer))
                            put("correct", attempt.correct)
                            put("official", attempt.official)
                            put("submittedAt", attempt.submittedAt.toString())
                        }
                    },
                ),
            )
            put("correctAnswer", json.encodeToJsonElement(if (answered) content.correctAnswer else emptyList()))
            put("evidenceText", if (answered) content.evidenceText?.let(::JsonPrimitive) ?: JsonNull else JsonNull)
            put("explanationOrigin", if (answered) JsonPrimitive(content.explanationOrigin) else JsonNull)
            put("explanationLearning", if (answered) JsonPrimitive(content.explanationLearning) else JsonNull)
        }
    }
}
