package jp.co.translacat.languagelearning.features.practice.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

@Serializable
internal data class ReadingRequest(
    val requestId: String,
    val mode: String,
    val originLanguage: String,
    val learningLanguage: String,
    val complexityBand: Int,
    val selectedKeywords: List<String>,
    val weakSignals: List<String>,
    val recentMistakes: List<String>,
    val generationDate: String,
)

internal data class ReadingPassage(val id: String, val text: String, val plans: List<JsonObject>)

internal object ReadingPrompts {
    private val json = Json { encodeDefaults = true }
    private fun strings(values: List<String>) = JsonArray(values.map(::JsonPrimitive))
    private fun wrapped(prefix: String, payload: JsonObject) = "$prefix\n<practice-data>\n$payload\n</practice-data>"

    fun passage(request: ReadingRequest, firstOrder: Int, previous: List<PracticeQuestionContent>): String {
        val number = if (firstOrder == 1) 1 else 2
        val template = ReadingAssets.template("passage:${request.mode}:${request.complexityBand}:$number")
        val payload = template.getValue("payload").jsonObject.toMutableMap()
        payload.putAll(
            buildJsonObject {
                put("requestId", request.requestId)
                put("learningLanguage", request.learningLanguage)
                put("selectedKeywords", strings(request.selectedKeywords))
                put("weakSignals", strings(request.weakSignals))
                put("recentMistakes", strings(request.recentMistakes))
                put("generationDate", request.generationDate)
                put(
                    "previousPassages",
                    buildJsonObject {
                        previous.filter { it.passageId != null && it.passageText != null }
                            .forEach { put(it.passageId!!, it.passageText!!) }
                    },
                )
                put(
                    "plannedQuestionSlots",
                    JsonArray(
                        slots(request, firstOrder).map { slot ->
                            buildJsonObject {
                                put("globalOrder", slot.globalOrder)
                                put("skillTag", slot.skillTag)
                                put("difficulty", slot.difficulty.name)
                                put("complexityBand", slot.complexityBand)
                                put(
                                    "questionDemand",
                                    ReadingAssets.questionRecipe(request.mode, slot.complexityBand, slot.skillTag),
                                )
                            }
                        },
                    ),
                )
            },
        )
        return template.getValue("prefix").jsonPrimitive.content + "<practice-data>\n${
            JsonObject(
                payload,
            )
        }\n</practice-data>"
    }

    fun candidates(
        request: ReadingRequest, firstOrder: Int, passage: ReadingPassage, selected: List<ReadingSlot>,
        previous: List<PracticeQuestionContent>, feedback: Map<Int, String>,
    ): String = wrapped(
        "Generate candidates for exactly the supplied candidateSlots.",
        buildJsonObject {
            put("requestId", request.requestId)
            put("domain", "READING")
            put("mode", request.mode)
            put("originLanguage", request.originLanguage)
            put("learningLanguage", request.learningLanguage)
            put("selectedKeywords", strings(request.selectedKeywords))
            put("weakSignals", strings(request.weakSignals))
            put("recentMistakes", strings(request.recentMistakes))
            put("generationDate", request.generationDate)
            put(
                "candidateSlots",
                JsonArray(
                    selected.map { slot ->
                        buildJsonObject {
                            put("order", slot.globalOrder - firstOrder + 1)
                            put("difficulty", slot.difficulty.name)
                            put("complexityBand", slot.complexityBand)
                            put("skillTag", slot.skillTag)
                            put("passageId", passage.id)
                            put("passageText", passage.text)
                            put("reviewTarget", false)
                            put(
                                "difficultyRecipe",
                                ReadingAssets.questionRecipe(request.mode, slot.complexityBand, slot.skillTag),
                            )
                            put(
                                "currentQuestionPlan",
                                passage.plans.single {
                                    it.getValue(
                                        "globalOrder",
                                    ).jsonPrimitive.int == slot.globalOrder
                                },
                            )
                            put("samePassageQuestionPlans", JsonArray(passage.plans))
                            if (request.mode in setOf("STRUCTURE", "CONTEXT_INFERENCE")) {
                                put(
                                    "samePassageTaskPosition",
                                    if (slot.globalOrder <= 3) slot.globalOrder else slot.globalOrder - 3,
                                )
                                put("samePassageTaskCount", if (slot.globalOrder <= 3) 3 else 2)
                            }
                            feedback[slot.globalOrder]?.let { put("retryFeedback", it.take(600)) }
                        }
                    },
                ),
            )
            put("excludedCanonicalKeys", JsonArray(emptyList()))
            put("excludedTargetExpressions", JsonArray(emptyList()))
            put("previousQuestions", JsonArray(previous.map { prior(it, true) }))
        },
    )

    fun verification(
        request: ReadingRequest, firstOrder: Int, questions: List<PracticeQuestionContent>,
        previous: List<PracticeQuestionContent>,
    ): String {
        val payload = buildJsonObject {
            put("domain", "READING")
            put("mode", request.mode)
            put("originLanguage", request.originLanguage)
            put("learningLanguage", request.learningLanguage)
            put(
                "questions",
                JsonArray(
                    questions.map { question ->
                        buildJsonObject {
                            put("order", question.order)
                            put("passageId", question.passageId?.let(::JsonPrimitive) ?: JsonNull)
                            put("passageText", question.passageText?.let(::JsonPrimitive) ?: JsonNull)
                            put("prompt", question.prompt)
                            put("options", json.encodeToJsonElement(question.options))
                            put("skillTag", question.skillTag)
                            put(
                                "questionDemand",
                                ReadingAssets.questionRecipe(
                                    request.mode, question.complexityBand, question.skillTag,
                                )["questionDemand"] ?: JsonNull,
                            )
                            if (request.mode == "STRUCTURE") {
                                val global = firstOrder + question.order - 1
                                put("samePassageTaskPosition", if (global <= 3) global else global - 3)
                                put("samePassageTaskCount", if (global <= 3) 3 else 2)
                            }
                        }
                    },
                ),
            )
            put(
                "readingEvidenceSpans",
                JsonArray(
                    ReadingSemantics.evidence(questions).map { span ->
                        buildJsonObject {
                            put("id", span.id)
                            put("passageId", span.passageId)
                            put("text", span.text)
                        }
                    },
                ),
            )
            put("previousReadingQuestions", JsonArray(previous.map { prior(it, false) }))
        }
        return ReadingAssets.template("verification:${request.mode}").getValue("prefix").jsonPrimitive.content + payload
    }

    fun repair(request: ReadingRequest, question: PracticeQuestionContent): String = wrapped(
        "Repair exactly the supplied wrong options while preserving every immutable field.",
        buildJsonObject {
            put("learningLanguage", request.learningLanguage)
            put("mode", request.mode)
            put("passageId", question.passageId?.let(::JsonPrimitive) ?: JsonNull)
            put("passageText", question.passageText?.let(::JsonPrimitive) ?: JsonNull)
            put("prompt", question.prompt)
            put(
                "correctOption",
                json.encodeToJsonElement(question.options.single { it.key == question.correctAnswer.single() }),
            )
            put(
                "wrongOptions",
                json.encodeToJsonElement(question.options.filter { it.key != question.correctAnswer.single() }),
            )
            put("evidenceText", question.evidenceText?.let(::JsonPrimitive) ?: JsonNull)
            put("skillTag", question.skillTag)
            put("difficulty", question.difficulty.name)
            put("complexityBand", question.complexityBand)
            put("questionType", question.questionType.name)
            put(
                "difficultyRecipe",
                ReadingAssets.questionRecipe(request.mode, question.complexityBand, question.skillTag),
            )
        },
    )

    fun explanation(request: ReadingRequest, questions: List<PracticeQuestionContent>): String = wrapped(
        "Create explanationOrigin for exactly these validated questions.",
        buildJsonObject {
            put("originLanguage", request.originLanguage)
            put("learningLanguage", request.learningLanguage)
            put(
                "questions",
                JsonArray(
                    questions.map { question ->
                        buildJsonObject {
                            put("order", question.order)
                            put("passageText", question.passageText?.let(::JsonPrimitive) ?: JsonNull)
                            put("prompt", question.prompt)
                            put("targetExpression", question.targetExpression?.let(::JsonPrimitive) ?: JsonNull)
                            put(
                                "correctAnswerText",
                                question.options.single { it.key == question.correctAnswer.single() }.text,
                            )
                            put("evidenceText", question.evidenceText?.let(::JsonPrimitive) ?: JsonNull)
                            put("explanationLearning", question.explanationLearning)
                        }
                    },
                ),
            )
        },
    )

    fun slots(request: ReadingRequest, firstOrder: Int) = PracticePolicy.slots(request.mode, request.complexityBand)
        .filter { it.globalOrder in if (firstOrder == 1) 1..3 else 4..5 }

    private fun prior(question: PracticeQuestionContent, withTarget: Boolean) = buildJsonObject {
        put("order", question.order)
        put("passageId", question.passageId?.let(::JsonPrimitive) ?: JsonNull)
        put("prompt", question.prompt)
        put("options", json.encodeToJsonElement(question.options))
        put("skillTag", question.skillTag)
        if (withTarget) put("targetExpression", question.targetExpression?.let(::JsonPrimitive) ?: JsonNull)
    }
}
