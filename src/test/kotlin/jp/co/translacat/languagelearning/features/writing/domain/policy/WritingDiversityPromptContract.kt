package jp.co.translacat.languagelearning.features.writing.domain.policy

import kotlinx.serialization.json.*

/** 승인된 prompt 추가 계약만 고정한다. 기존 Python golden 원문을 변경 없이 추가 계약과 함께 검증한다. */
internal object WritingDiversityPromptContract {
    val generation = """
        Compare each candidate with EVERY R-numbered retainedCurrentWritingTasks entry before choosing it.
        Use the visible content, providedFacts, requiredIntents and responseConstraints to identify the
        core facts, communicative purpose and minimum production required for a valid answer.
        Change the actual situation/purpose or required meaning; changing metadata labels, objects,
        names, wording, or a minor timing detail alone does not make the same core task distinct.
        A shared selected topic or grammar pattern is allowed when the required message is different.
        Null guidance means unavailable history, not permission to invent facts or learner weaknesses.
    """.trimIndent()

    val review = """
        Before deciding TASK_VALIDITY, compare the candidate against EVERY R-numbered retainedCurrentItems entry.
        For each pair, identify the core facts, communicative purpose and minimum required production from
        visible content plus providedFacts, requiredIntents and responseConstraints. Compare the actual
        required messages, not proposed intent/archetype labels or the generator's semanticSummary.
        A changed label such as reporting versus explaining does not make the same core message distinct.
        Different names, objects, wording or minor timing details alone do not establish a new task.
        If the same core situation/purpose and required meaning recur, TASK_VALIDITY must FAIL with the
        existing DIVERSITY_SCENE_REPETITION issue. If the distinction is uncertain, use TASK_VALIDITY UNSURE.
        Sharing a topic or grammar pattern alone is valid when the required message or purpose is different.
        Null retained guidance is unavailable information: do not reconstruct it or claim missing facts.
        R IDs anchor comparisons only; evidenceSegmentIds must still cite the candidate's actual task segments.
        Keep the existing response schema and difficulty rubric. Do not add comparison fields or infer a band from history.
    """.trimIndent()

    private val calibration = """
        Before estimating, work out internally a simplest adequate learningLanguage answer that
        preserves every required meaning. Do not return that answer. Separate background facts
        from required relationships: several fields can repeat or explain one simple reason or
        ordinary request, rather than require several connected relations. Apply the complete
        rubric to the answer's necessary meaning, not the number of facts, intents, sentences,
        or polite phrases. A routine date or an optional elaborate phrasing is not itself a
        nuanced condition, concession, indirect stance, or interacting qualification. Conversely,
        do not simplify away a required relation, exception, or scope to justify a lower band.
        Keep this estimate invariant when the same required message is split across GUIDED
        fields instead of one TRANSLATION sentence. A familiar request with one simple reason
        still fits BASIC; its background date, audience and ordinary politeness do not promote it.
        A required connected report of completed work, remaining work and its future timing
        fits the INTERMEDIATE time-relation description; ordinary polite phrasing alone does
        not add the nuance required by UPPER_INTERMEDIATE. These illustrate the supplied rubric,
        not exceptions to it. Determine the band before filling conditional productionDemandChecks;
        those diagnostics must not pull an otherwise simpler task toward band 4 or 5.
    """.trimIndent()

    private val typeObservation = """
        Before choosing a band or verdict, fill the type-specific observation required by the schema.
        For FREE, contentChoiceObservation must name the substantive content the task requires the
        learner to choose, using a short choiceDescription. SUBSTANTIVE_CHOICE means that actual
        choice remains; EXPRESSION_ONLY means only wording or optional additions remain; UNSURE
        means the visible task does not establish which is true. EXPRESSION_ONLY requires
        TASK_VALIDITY FAIL with TASK_TYPE; UNSURE requires TASK_VALIDITY UNSURE.
        For GUIDED, productionObservation must briefly state minimumRequiredMeaning and
        necessaryRelations from the visible task. Describe what an adequate answer must communicate,
        not an answer, field counts or optional sophistication. If no connected relation is required,
        say so. Use this observation when applying the existing all-band rubric; it is not a new score.
        Both observations must cite actual task segment IDs only, never N1, history or hidden originals.
    """.trimIndent()

    fun calibratedInstructions(original: String): String = original.replace(
        "visible prompt actually asks, not how sophisticated a learner could choose to be.\n",
        "visible prompt actually asks, not how sophisticated a learner could choose to be.\n$calibration\n",
    ).replace(
        "specialist knowledge, contradictory requirements or non-language task.\n",
        "specialist knowledge, contradictory requirements or non-language task.\n" +
            "For FREE, identify a meaningful action, reason, example, position, or plan the learner\n" +
            "must choose. Merely selecting wording for a fully prescribed message is not FREE;\n" +
            "reject that task as TASK_TYPE even when its guidance arrays are empty.\n" +
            "For example, asking the learner to communicate a fixed action X for a fixed reason Y\n" +
            "does not leave content choice. Asking the learner to choose X or supply Y does.\n" +
            "An imagined setting, a choice of wording, or the possibility of adding optional details\n" +
            "does not turn the first task into FREE. Infer the observed type from the actual task,\n" +
            "not merely from its supplied writingType label.\n",
    ).replace(
        "proof of truth: actually check every criterion against the supplied text.\n",
        "proof of truth: actually check every criterion against the supplied text.\n$typeObservation\n",
    )

    // 이 기존 golden의 두 이력에는 안내 필드가 없으며 없는 원문을 추정해서 채우지 않는다.
    fun historicalRows(original: List<JsonElement>) = JsonArray(original.mapIndexed { index, entry ->
        val item = entry.jsonObject
        buildJsonObject {
            put("id", "R${index + 1}")
            put("content", item.getValue("content"))
            put("providedFacts", JsonNull)
            put("requiredIntents", JsonNull)
            put("responseConstraints", JsonNull)
            put("semanticSummary", item["semanticSummary"] ?: JsonNull)
        }
    })
}
